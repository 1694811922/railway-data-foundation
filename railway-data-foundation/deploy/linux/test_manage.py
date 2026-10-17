"""Pure deployment checks; no Docker daemon, cloud account or business database."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).with_name('manage.py'))
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.state = Path(self.temporary.name) / 'private'
        self.permission = patch.object(deployment.os, 'chown', create=True)
        self.permission.start()
        self.addCleanup(self.permission.stop)
        with contextlib.redirect_stdout(io.StringIO()):
            deployment.initialize(self.state)

    def env(self, name):
        return dict(line.split('=', 1) for line in (self.state / 'config' / (name + '.env')).read_text(encoding='utf-8').splitlines())

    def test_repeated_initialization_keeps_all_secrets(self):
        before = {str(p.relative_to(self.state)): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.state.rglob('*') if p.is_file()}
        with contextlib.redirect_stdout(io.StringIO()):
            deployment.initialize(self.state)
        after = {str(p.relative_to(self.state)): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.state.rglob('*') if p.is_file()}
        self.assertEqual(before, after)

    def test_missing_or_changed_credentials_fail_closed(self):
        (self.state / 'config/demand.env').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or changed'):
            deployment.initialize(self.state)

    def test_first_boot_permission_is_not_recreated_after_success(self):
        marker = self.state / 'bootstrap/first-boot.allowed'
        self.assertTrue(marker.is_file())
        marker.chmod(0o600)
        marker.unlink()
        with contextlib.redirect_stdout(io.StringIO()):
            deployment.initialize(self.state)
        self.assertFalse(marker.exists())

    def test_unmarked_existing_data_is_not_overwritten(self):
        (self.state / 'manifest.json').unlink()
        with self.assertRaisesRegex(ValueError, 'without a manifest'):
            deployment.initialize(self.state)

    def test_only_cookie_setting_can_change_foundation_configuration(self):
        path = self.state / 'config/foundation.env'
        original = path.read_text(encoding='utf-8')
        path.write_text(original.replace('COOKIE_SECURE=false', 'COOKIE_SECURE=true'), encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()):
            deployment.verify_state(self.state)
        path.write_text(original + 'PROVIDER_CONTROL_TOKEN=changed\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'configuration changed'):
            deployment.verify_state(self.state)

    def test_roles_have_only_required_database_and_kafka_secrets(self):
        foundation, provider, demand = (self.env(name) for name in ('foundation', 'provider', 'demand'))
        self.assertEqual(foundation['SOURCE_DB_HOST'], '127.0.0.1')
        self.assertEqual(foundation['SOURCE_DB_PORT'], '13306')
        self.assertEqual(foundation['PROVIDER_CONTROL_TOKEN'], provider['PROVIDER_CONTROL_TOKEN'])
        self.assertNotEqual(foundation['FOUNDATION_CREDENTIAL_KEY'], demand['DEMAND_CREDENTIAL_KEY'])
        self.assertNotIn('KAFKA_SUBSCRIPTION_ADMIN_PASSWORD', provider)
        self.assertNotIn('PROVIDER_KAFKA_PASSWORD', foundation)
        self.assertNotIn('PROVIDER_CONTROL_TOKEN', demand)
        self.assertNotIn('KAFKA_SUBSCRIPTION_ADMIN_PASSWORD', demand)
        self.assertEqual(foundation['FOUNDATION_REGISTRATION_LOCAL_BOOTSTRAP_ENABLED'], 'false')

    def test_source_reader_has_no_write_privilege_and_seed_is_source_only(self):
        sql = (self.state / 'config/mysql-init/10-init.sql').read_text(encoding='utf-8')
        self.assertIn('GRANT SELECT, SHOW VIEW ON port_source_db.*', sql)
        self.assertNotIn('GRANT ALL PRIVILEGES ON port_source_db.*', sql)
        self.assertIn('USE port_source_db;', sql)
        self.assertIn('GZ_PORT_DEMO', sql)
        self.assertNotIn('INSERT INTO data_resource', sql)

    def test_kafka_is_authenticated_loopback_and_retention_is_bounded(self):
        config = (self.state / 'config/server.properties').read_text(encoding='utf-8')
        self.assertIn('BROKER://127.0.0.1:19092', config)
        self.assertIn('allow.everyone.if.no.acl.found=false', config)
        self.assertIn('log.retention.ms=604800000', config)
        self.assertIn('log.retention.bytes=2147483648', config)
        self.assertNotIn('0.0.0.0', config)

    def test_gateway_channels_do_not_proxy_business_data_through_foundation(self):
        config = (self.state / 'config/nginx.conf').read_text(encoding='utf-8')
        self.assertIn('listen 127.0.0.1:8080', config)
        self.assertIn('location /provider/ { proxy_pass http://127.0.0.1:18081/;', config)
        self.assertIn('location /demand/ { proxy_pass http://127.0.0.1:18082/;', config)
        self.assertIn('proxy_request_buffering off', config)
        self.assertIn('proxy_buffering off', config)
        self.assertNotIn('$request_body', config)

    def test_https_certificate_is_separate_from_credential_files(self):
        deployment.configure_nginx(self.state, 'railway.example.org')
        config = (self.state / 'config/nginx.conf').read_text(encoding='utf-8')
        self.assertIn('listen 443 ssl', config)
        self.assertIn('ssl_certificate_key /etc/railway-tls/privkey.pem', config)
        self.assertNotIn('listen 0.0.0.0:8080', config)
        self.assertIn('location / { return 404; }', config)

    def test_kafka_health_uses_supported_authenticated_command(self):
        import re
        helper = (deployment.PROJECT / 'scripts/java/LocalKafka.java').read_text(encoding='utf-8')
        script = (deployment.HERE / 'kafka-entrypoint.sh').read_text(encoding='utf-8')
        command = re.search(r'health\) run_local (\w+)', script).group(1)
        self.assertIn('case "' + command + '"', helper)
        self.assertEqual('check', command)

    def test_initialization_output_contains_no_password_or_key(self):
        other = Path(self.temporary.name) / 'second'
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            deployment.initialize(other)
        for name in ('foundation', 'provider', 'demand'):
            for line in (other / 'config' / (name + '.env')).read_text(encoding='utf-8').splitlines():
                key, value = line.split('=', 1)
                if 'PASSWORD' in key or 'TOKEN' in key or 'CREDENTIAL_KEY' in key:
                    self.assertNotIn(value, output.getvalue())


    def legacy_monitor_configuration(self):
        for service, prefixes in [('foundation',('FOUNDATION_RECEIVER_TELEMETRY_TOKEN=','RECEIVER_TELEMETRY_NODE_ID=')),('demand',('DEMAND_TELEMETRY_TOKEN=','DEMAND_TELEMETRY_NODE_ID='))]:
            file=self.state/'config'/f'{service}.env'
            file.write_text(''.join(line+'\n' for line in file.read_text().splitlines() if not line.startswith(prefixes)))
        manifest=self.state/'manifest.json';data=json.loads(manifest.read_text())
        data['foundation_config_digest']=deployment.foundation_digest(self.state/'config/foundation.env')
        data['fixed_config']['config/demand.env']=hashlib.sha256((self.state/'config/demand.env').read_bytes()).hexdigest()
        manifest.write_text(json.dumps(data))

    def test_monitor_upgrade_preserves_database_keys_and_is_idempotent(self):
        self.legacy_monitor_configuration();before={name:self.env(name) for name in ['foundation','demand','provider']}
        with contextlib.redirect_stdout(io.StringIO()):deployment.enable_receiver_monitor(self.state)
        for service in before:
            for key,value in before[service].items():self.assertEqual(value,self.env(service)[key])
        self.assertEqual(self.env('foundation')['FOUNDATION_RECEIVER_TELEMETRY_TOKEN'],self.env('demand')['DEMAND_TELEMETRY_TOKEN'])
        saved=(self.state/'manifest.json').read_bytes()
        with contextlib.redirect_stdout(io.StringIO()):deployment.enable_receiver_monitor(self.state)
        self.assertEqual(saved,(self.state/'manifest.json').read_bytes())

    def test_fresh_monitor_secret_is_separate_from_provider_control(self):
        self.assertEqual(self.env('foundation')['FOUNDATION_RECEIVER_TELEMETRY_TOKEN'],self.env('demand')['DEMAND_TELEMETRY_TOKEN'])
        self.assertNotEqual(self.env('foundation')['FOUNDATION_RECEIVER_TELEMETRY_TOKEN'],self.env('foundation')['PROVIDER_CONTROL_TOKEN'])
        self.assertNotIn('FOUNDATION_RECEIVER_TELEMETRY_TOKEN',self.env('provider'))

    def test_monitor_upgrade_refuses_modified_original_state(self):
        self.legacy_monitor_configuration()
        with (self.state/'config/demand.env').open('a') as f:f.write('UNEXPECTED=change\n')
        with self.assertRaises(ValueError):deployment.enable_receiver_monitor(self.state)
        self.assertFalse((self.state/'monitor-upgrade-backup').exists())

    def test_monitor_upgrade_failure_restores_original_configuration(self):
        self.legacy_monitor_configuration();files=['config/foundation.env','config/demand.env','manifest.json'];before={name:(self.state/name).read_bytes() for name in files}
        with patch.object(deployment.os,'replace',side_effect=OSError('test interruption')):
            with self.assertRaises(OSError):deployment.enable_receiver_monitor(self.state)
        for name in files:self.assertEqual(before[name],(self.state/name).read_bytes())


if __name__ == '__main__':
    unittest.main()
