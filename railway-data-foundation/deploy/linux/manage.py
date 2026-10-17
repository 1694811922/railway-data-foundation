#!/usr/bin/env python3
"""Single-host Linux deployment operations. Never log secret values."""
import argparse
import base64
import datetime
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
STATE = HERE / '.private'
PORTS = (13306, 19092, 19093, 18080, 18081, 18082, 8080)
SERVICES = ('mysql', 'kafka', 'foundation', 'provider', 'demand', 'web')


def write(path, value, mode=0o600, owner=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8', newline='\n') as output:
        output.write(value)
    path.chmod(mode)
    if owner is not None and sys.platform == 'linux':
        os.chown(path, owner, owner)


def environment(path, values):
    write(path, ''.join(f'{key}={value}\n' for key, value in values.items()))


def foundation_digest(path):
    content = '\n'.join(line for line in path.read_text(encoding='utf-8').splitlines() if not line.startswith('FOUNDATION_SESSION_COOKIE_SECURE='))
    return hashlib.sha256(content.encode()).hexdigest()


def configure_nginx(state, domain=None):
    # No query strings, headers or payloads in access logs. Provider/Demand are
    # direct upstreams; Foundation never proxies business data. No disk buffering.
    proxy = '''
    root /usr/share/nginx/html;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy same-origin always;
    location = /gateway-health { access_log off; return 200 'UP'; }
    location /api/ { proxy_pass http://127.0.0.1:18080; }
    location /provider/ { proxy_pass http://127.0.0.1:18081/; }
    location /demand/ { proxy_pass http://127.0.0.1:18082/; }
    location / { try_files $uri $uri/ /index.html; }
'''
    servers = 'server { listen 127.0.0.1:8080;\n' + proxy + '}\n'
    if domain:
        servers = '''server { listen 127.0.0.1:8080;
    location = /gateway-health { access_log off; return 200 'UP'; }
    location / { return 404; }
}\n'''
        servers += f'''server {{ listen 80; server_name {domain}; return 308 https://{domain}$request_uri; }}
server {{ listen 443 ssl; server_name {domain};
    ssl_certificate /etc/railway-tls/fullchain.pem;
    ssl_certificate_key /etc/railway-tls/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
''' + proxy + '}\n'
    write(state / 'config/nginx.conf', '''user nginx;
worker_processes auto;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;
events { worker_connections 1024; }
http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  log_format safe '$remote_addr [$time_local] "$request_method $uri" $status $body_bytes_sent';
  access_log /dev/stdout safe;
  server_tokens off;
  client_max_body_size 2m;
  client_body_buffer_size 2m;
  proxy_http_version 1.1;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Forwarded-For $remote_addr;
  proxy_set_header Cookie $http_cookie;
  proxy_set_header X-CSRF-TOKEN $http_x_csrf_token;
  proxy_connect_timeout 5s;
  proxy_read_timeout 60s;
  proxy_send_timeout 60s;
  proxy_buffering off;
  proxy_request_buffering off;
  proxy_max_temp_file_size 0;
  proxy_cache off;
  proxy_next_upstream off;
''' + servers + '}\n', 0o644)


def initialize(state=STATE):
    if state.exists():
        manifest = state / 'manifest.json'
        if not manifest.is_file():
            raise ValueError('Private state already exists without a manifest; restore it, do not regenerate keys')
        spec = json.loads(manifest.read_text(encoding='utf-8'))
        if spec.get('version') != 1:
            raise ValueError('Unknown private state version')
        for name, digest in spec['fixed_config'].items():
            path = state / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ValueError('Original credential/configuration missing or changed; refusing reinitialization')
        if foundation_digest(state / 'config/foundation.env') != spec['foundation_config_digest']:
            raise ValueError('Original Foundation configuration changed; restore it instead of reinitializing')
        print('Existing private configuration verified; keys and passwords were preserved.')
        return
    state.mkdir(parents=True, mode=0o700)
    state.chmod(0o700)
    config = state / 'config'
    config.mkdir(mode=0o700)
    for name in ('data/mysql', 'data/kafka', 'data/provider', 'data/demand', 'public', 'bootstrap', 'tls', 'backups'):
        directory = state / name
        directory.mkdir(parents=True, mode=0o700)
        if sys.platform == 'linux':
            os.chown(directory, 999 if name == 'data/mysql' else 10001, 999 if name == 'data/mysql' else 10001)
        if name == 'public':
            directory.chmod(0o755)
    write(state / 'bootstrap/first-boot.allowed', 'fresh private state\n', 0o400, 10001)
    passwords = {name: secrets.token_hex(32) for name in ('root', 'foundation', 'demand', 'source', 'provider-control', 'broker', 'admin', 'probe', 'producer', 'controller')}
    write(config / 'mysql-root', passwords['root'], 0o400, 999)
    write(config / 'mysql-client.cnf', f"[client]\nuser=root\npassword={passwords['root']}\n", 0o400, 999)
    write(config / 'source-client.cnf', f"[client]\nhost=127.0.0.1\nport=13306\nuser=source_reader\npassword={passwords['source']}\ndatabase=port_source_db\n")
    sql = ''.join(f'CREATE DATABASE {name} CHARACTER SET utf8mb4;\n' for name in ('foundation_db', 'port_source_db', 'demand_receiver_db'))
    for user, password, database, privileges in (
        ('foundation_app', passwords['foundation'], 'foundation_db', 'ALL PRIVILEGES'),
        ('demand_receiver_app', passwords['demand'], 'demand_receiver_db', 'ALL PRIVILEGES'),
        ('source_reader', passwords['source'], 'port_source_db', 'SELECT, SHOW VIEW')):
        for host in ('127.0.0.1', 'localhost'):
            sql += f"CREATE USER '{user}'@'{host}' IDENTIFIED BY '{password}';\nGRANT {privileges} ON {database}.* TO '{user}'@'{host}';\n"
    sql += 'USE port_source_db;\n'
    for path in sorted((PROJECT / 'sql/source').glob('V*.sql')):
        sql += path.read_text(encoding='utf-8-sig') + '\n'
    write(config / 'mysql-init/10-init.sql', sql, 0o400, 999)
    write(config / 'mysql-init/20-finished.sh', '#!/bin/sh\nset -eu\ntouch /var/lib/mysql/.railway-init-complete\n', 0o500, 999)
    (config / 'mysql-init').chmod(0o700)
    if sys.platform == 'linux':
        os.chown(config / 'mysql-init', 999, 999)
    identity = {'port': '19092', 'controller.port': '19093', 'topic': 'port.container.event.v1'}
    for role in ('broker', 'admin', 'probe', 'producer', 'controller'):
        identity[role + '.username'] = 'cloud_' + role
        identity[role + '.password'] = passwords[role]
    write(config / 'kafka.identity', ''.join(f'{k}={v}\n' for k, v in identity.items()), 0o400, 10001)
    server = '''process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:19093
listeners=BROKER://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093
advertised.listeners=BROKER://127.0.0.1:19092
listener.security.protocol.map=BROKER:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=BROKER
sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
sasl.mechanism.inter.broker.protocol=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.broker.sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
listener.name.controller.sasl.enabled.mechanisms=PLAIN
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:cloud_broker;User:cloud_controller;User:cloud_admin
log.dirs=/var/lib/railway/kafka/data
auto.create.topics.enable=false
delete.topic.enable=false
num.partitions=3
default.replication.factor=1
offsets.topic.replication.factor=1
offsets.topic.num.partitions=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
log.segment.bytes=16777216
log.retention.ms=604800000
log.retention.bytes=2147483648
group.initial.rebalance.delay.ms=0
'''
    server += f'listener.name.broker.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="cloud_broker" password="{passwords["broker"]}" user_cloud_broker="{passwords["broker"]}" user_cloud_admin="{passwords["admin"]}";\n'
    server += f'listener.name.broker.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="cloud_broker" password="{passwords["broker"]}";\n'
    server += f'listener.name.controller.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="cloud_controller" password="{passwords["controller"]}" user_cloud_controller="{passwords["controller"]}";\n'
    write(config / 'server.properties', server, 0o400, 10001)
    monitor_token = secrets.token_urlsafe(32)
    monitor_node = 'receiver-' + secrets.token_hex(8)
    database_url = lambda name: f'jdbc:mysql://127.0.0.1:13306/{name}?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=UTC'
    foundation = dict(FOUNDATION_BIND='127.0.0.1', FOUNDATION_PORT='18080', FOUNDATION_DB_URL=database_url('foundation_db'), FOUNDATION_DB_USER='foundation_app', FOUNDATION_DB_PASSWORD=passwords['foundation'], FOUNDATION_CREDENTIAL_KEY=base64.b64encode(secrets.token_bytes(32)).decode(), PROVIDER_CONTROL_TOKEN=passwords['provider-control'], PROVIDER_REST_BASE_URL='http://127.0.0.1:18081', PROVIDER_SOURCE_ENV_PREFIX='SOURCE_DB', SOURCE_DB_HOST='127.0.0.1', SOURCE_DB_PORT='13306', SOURCE_DB_DATABASE='port_source_db', SOURCE_DB_USER='source_reader', SOURCE_DB_PASSWORD=passwords['source'], SOURCE_DB_SSL_MODE='DISABLED', FOUNDATION_REGISTRATION_LOCAL_BOOTSTRAP_ENABLED='false', FOUNDATION_SESSION_COOKIE_SECURE='false', KAFKA_SUBSCRIPTION_EXECUTION_ENABLED='true', PROVIDER_TELEMETRY_NODE_ID='provider-cloud')
    provider = dict(PROVIDER_BIND='127.0.0.1', PROVIDER_PORT='18081', PROVIDER_FOUNDATION_BASE_URL='http://127.0.0.1:18080', PROVIDER_SOURCE_DB_URL=database_url('port_source_db'), PROVIDER_SOURCE_DB_USER='source_reader', PROVIDER_SOURCE_DB_PASSWORD=passwords['source'], PROVIDER_SOURCE_TIME_OFFSET='+08:00', PROVIDER_CONTROL_TOKEN=passwords['provider-control'], PROVIDER_TELEMETRY_NODE_ID='provider-cloud', PROVIDER_TELEMETRY_ENABLED='true', PROVIDER_TELEMETRY_SPOOL_DIR='/var/lib/railway/provider/telemetry')
    for target, prefix, role, mechanism in ((foundation, 'FOUNDATION_KAFKA_', 'probe', 'SCRAM-SHA-512'), (foundation, 'KAFKA_SUBSCRIPTION_ADMIN_', 'admin', 'PLAIN'), (provider, 'PROVIDER_KAFKA_', 'producer', 'SCRAM-SHA-512')):
        target.update({prefix + 'BOOTSTRAP_SERVERS': '127.0.0.1:19092', prefix + 'SECURITY_PROTOCOL': 'SASL_PLAINTEXT', prefix + 'SASL_MECHANISM': mechanism, prefix + 'USERNAME': 'cloud_' + role, prefix + 'PASSWORD': passwords[role]})
        if role != 'admin':
            target.update({prefix + 'AUTH_TYPE': 'SASL', prefix + 'TOPIC': 'port.container.event.v1'})
    demand = dict(DEMAND_BIND='127.0.0.1', DEMAND_PORT='18082', DEMAND_FOUNDATION_URL='http://127.0.0.1:18080', DEMAND_DB_NAME='demand_receiver_db', DEMAND_DB_URL=database_url('demand_receiver_db'), DEMAND_DB_USER='demand_receiver_app', DEMAND_DB_PASSWORD=passwords['demand'], DEMAND_CREDENTIAL_KEY=base64.b64encode(secrets.token_bytes(32)).decode(), DEMAND_LOG_FILE='/var/lib/railway/demand/demand.log')
    foundation.update(FOUNDATION_RECEIVER_TELEMETRY_TOKEN=monitor_token, RECEIVER_TELEMETRY_NODE_ID=monitor_node)
    demand.update(DEMAND_TELEMETRY_TOKEN=monitor_token, DEMAND_TELEMETRY_NODE_ID=monitor_node)
    for name, values in (('foundation', foundation), ('provider', provider), ('demand', demand)):
        environment(config / (name + '.env'), values)
    configure_nginx(state)
    fixed = ('config/mysql-root', 'config/mysql-client.cnf', 'config/source-client.cnf', 'config/mysql-init/10-init.sql', 'config/mysql-init/20-finished.sh', 'config/kafka.identity', 'config/server.properties', 'config/provider.env', 'config/demand.env')
    manifest = {'version': 1, 'created_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'fixed_config': {name: hashlib.sha256((state / name).read_bytes()).hexdigest() for name in fixed}, 'foundation_key_digest': hashlib.sha256(foundation['FOUNDATION_CREDENTIAL_KEY'].encode()).hexdigest(), 'foundation_config_digest': foundation_digest(config / 'foundation.env'), 'mode': 'local'}
    write(state / 'manifest.json', json.dumps(manifest, indent=2) + '\n')
    print('Fresh private state created. No secrets printed; Windows data was not imported.')


def verify_state(state=STATE):
    initialize(state)
    values = dict(line.split('=', 1) for line in (state / 'config/foundation.env').read_text(encoding='utf-8').splitlines() if '=' in line)
    spec = json.loads((state / 'manifest.json').read_text(encoding='utf-8'))
    if hashlib.sha256(values['FOUNDATION_CREDENTIAL_KEY'].encode()).hexdigest() != spec['foundation_key_digest']:
        raise ValueError('Original Foundation encryption key changed; restore the original key')
    return spec



def enable_receiver_monitor(state=STATE):
    spec = verify_state(state)
    foundation_path = state / 'config/foundation.env'
    demand_path = state / 'config/demand.env'
    def read_env(path):
        return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line)
    foundation, demand = read_env(foundation_path), read_env(demand_path)
    left, right = foundation.get('FOUNDATION_RECEIVER_TELEMETRY_TOKEN'), demand.get('DEMAND_TELEMETRY_TOKEN')
    if left or right:
        if left != right or not re.fullmatch(r'[A-Za-z0-9_-]{43,128}', left or '') or foundation.get('RECEIVER_TELEMETRY_NODE_ID') != demand.get('DEMAND_TELEMETRY_NODE_ID') or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', foundation.get('RECEIVER_TELEMETRY_NODE_ID', '')):
            raise ValueError('Monitoring configuration is incomplete or inconsistent; restore the original configuration')
        print('Existing monitoring configuration preserved; no keys rotated.')
        return
    token, node = secrets.token_urlsafe(32), 'receiver-' + secrets.token_hex(8)
    original = {path: path.read_bytes() for path in (foundation_path, demand_path, state / 'manifest.json')}
    backup = state / 'monitor-upgrade-backup'
    if backup.exists():
        raise ValueError('An earlier upgrade backup exists; inspect it before retrying')
    backup.mkdir(mode=0o700)
    for path, content in original.items():
        write(backup / path.name, content.decode('utf-8'))
    try:
        foundation.update(FOUNDATION_RECEIVER_TELEMETRY_TOKEN=token, RECEIVER_TELEMETRY_NODE_ID=node)
        demand.update(DEMAND_TELEMETRY_TOKEN=token, DEMAND_TELEMETRY_NODE_ID=node)
        def atomic(path, value):
            temporary = path.with_name(path.name + '.monitor-new')
            write(temporary, value)
            os.replace(temporary, path)
        atomic(foundation_path, ''.join(f'{k}={v}\n' for k, v in foundation.items()))
        atomic(demand_path, ''.join(f'{k}={v}\n' for k, v in demand.items()))
        spec['foundation_config_digest'] = foundation_digest(foundation_path)
        spec['fixed_config']['config/demand.env'] = hashlib.sha256(demand_path.read_bytes()).hexdigest()
        atomic(state / 'manifest.json', json.dumps(spec, indent=2) + '\n')
        verify_state(state)
    except BaseException:
        for path, content in original.items():
            write(path, content.decode('utf-8'))
        raise
    print('Monitoring configuration added. Existing DB passwords and encryption keys preserved; recreate Foundation and Demand.')


def compose(*args, **kwargs):
    return subprocess.run(['docker', 'compose', '-f', str(HERE / 'compose.yml'), *args], check=True, **kwargs)


def check_host():
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise ValueError('Run on the Ubuntu server using sudo python3 deploy/linux/manage.py ...')


def preflight():
    if shutil.disk_usage(HERE).free < 15 * 1024**3:
        raise ValueError('At least 15 GiB free disk is required for first build; allow more for data growth')
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise ValueError('Unset DOCKER_HOST/DOCKER_CONTEXT and use the local Docker socket')
    for port in PORTS:
        with socket.socket() as listener:
            try:
                listener.bind(('127.0.0.1', port))
            except OSError:
                raise ValueError(f'Port {port} is occupied; inspect existing services, do not overwrite them')
    server = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}'], text=True))
    if server.get('OSType') != 'linux' or any('rootless' in item or 'userns' in item for item in server.get('SecurityOptions', [])):
        raise ValueError('This package requires rootful Linux Docker without user namespace remapping')
    endpoint = subprocess.check_output(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], text=True).strip()
    if endpoint != 'unix:///var/run/docker.sock':
        raise ValueError('Use the local server Docker socket, not a remote Docker context')
    subprocess.run(['docker', 'compose', 'version'], check=True)
    print('First-start host checks passed. No ports were opened.')


def administrator():
    verify_state()
    username = input('Administrator username: ').strip()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{2,63}', username):
        raise ValueError('Invalid administrator username')
    password = getpass.getpass('Administrator password (12+ characters, <=72 UTF-8 bytes): ')
    if len(password) < 12 or len(password.encode('utf-8')) > 72:
        raise ValueError('Password length is invalid')
    if password != getpass.getpass('Repeat password: '):
        raise ValueError('Passwords do not match')
    child_env = os.environ.copy()
    child_env.update(FOUNDATION_BOOTSTRAP_ADMIN_USERNAME=username, FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD=password)
    try:
        compose('run', '--rm', '--no-deps', '-e', 'FOUNDATION_BOOTSTRAP_ADMIN_USERNAME', '-e', 'FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD', 'foundation', '--server.port=0', '--foundation.initialize-only=true', env=child_env)
    finally:
        child_env.pop('FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD', None)
        password = None
    print('Initialization finished; existing administrator accounts are not overwritten.')


def enable_https(domain, certificate, key):
    spec = verify_state()
    if not re.fullmatch(r'(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', domain) or '.' not in domain or '..' in domain:
        raise ValueError('Use a DNS domain name without scheme, path, wildcard or port')
    if any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part) for part in domain.split('.')):
        raise ValueError('Invalid domain labels')
    try:
        ipaddress.ip_address(domain)
    except ValueError:
        pass
    else:
        raise ValueError('Configure a DNS domain, not an IP address')
    subprocess.run(['openssl', 'x509', '-in', certificate, '-checkend', '0', '-noout'], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['openssl', 'x509', '-in', certificate, '-checkhost', domain, '-noout'], check=True, stdout=subprocess.DEVNULL)
    public_cert = subprocess.check_output(['openssl', 'x509', '-in', certificate, '-pubkey', '-noout'])
    public_key = subprocess.check_output(['openssl', 'pkey', '-in', key, '-passin', 'pass:', '-pubout'])
    if public_cert != public_key:
        raise ValueError('Certificate does not match private key')
    write(STATE / 'tls/fullchain.pem', Path(certificate).read_text(encoding='ascii'))
    write(STATE / 'tls/privkey.pem', Path(key).read_text(encoding='ascii'))
    env_path = STATE / 'config/foundation.env'
    text = env_path.read_text(encoding='utf-8')
    text = re.sub(r'^FOUNDATION_SESSION_COOKIE_SECURE=.*$', 'FOUNDATION_SESSION_COOKIE_SECURE=true', text, flags=re.M)
    write(env_path, text)
    configure_nginx(STATE, domain)
    spec.update(mode='https', domain=domain)
    write(STATE / 'manifest.json', json.dumps(spec, indent=2) + '\n')
    print('HTTPS configuration prepared. Recreate Foundation and web; verify the public certificate and log in again.')


def backup():
    verify_state()
    running = set(compose('ps', '--status', 'running', '--services', capture_output=True, text=True).stdout.split())
    if not set(SERVICES).issubset(running):
        raise ValueError('Backup requires the complete healthy stack; inspect service status first')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = STATE / 'backups' / ('railway-' + stamp + '.tar.gz')
    if output.exists():
        raise ValueError('Backup file already exists')
    temporary = STATE / 'backups' / ('databases-' + stamp + '.sql')
    try:
        compose('stop', 'web', 'demand', 'provider', 'foundation', 'kafka')
        with temporary.open('xb') as sql:
            temporary.chmod(0o600)
            compose('exec', '-T', 'mysql', 'mysqldump', '--defaults-extra-file=/run/secrets/mysql-client.cnf', '--single-transaction', '--routines', '--events', '--triggers', '--hex-blob', '--set-gtid-purged=OFF', '--databases', 'foundation_db', 'port_source_db', 'demand_receiver_db', stdout=sql)
        with output.open('xb') as target:
            output.chmod(0o600)
            with tarfile.open(fileobj=target, mode='w:gz') as archive:
                archive.add(temporary, arcname='databases.sql')
                for name in ('config', 'manifest.json', 'tls', 'public', 'bootstrap', 'data/kafka', 'data/provider'):
                    archive.add(STATE / name, arcname=name)
        print('Private backup written:', output)
    except BaseException:
        output.unlink(missing_ok=True)
        raise
    finally:
        temporary.unlink(missing_ok=True)
        compose('up', '-d', '--wait', '--wait-timeout', '240')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='command', required=True)
    for name in ('init', 'verify', 'preflight', 'admin', 'backup', 'enable-receiver-monitor'):
        actions.add_parser(name)
    https = actions.add_parser('https')
    https.add_argument('--domain', required=True)
    https.add_argument('--cert', required=True)
    https.add_argument('--key', required=True)
    args = parser.parse_args()
    check_host()
    if args.command == 'init':
        initialize()
    elif args.command == 'verify':
        verify_state()
    elif args.command == 'preflight':
        preflight()
    elif args.command == 'admin':
        administrator()
    elif args.command == 'https':
        enable_https(args.domain.lower(), args.cert, args.key)
    elif args.command == 'enable-receiver-monitor':
        enable_receiver_monitor()
    elif args.command == 'backup':
        backup()


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Exception arguments from a subprocess never contain a password value.
        print('Deployment operation failed:', str(error), file=sys.stderr)
        sys.exit(1)
