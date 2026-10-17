#!/usr/bin/env python3
"""Build a source-only deployment ZIP on the development computer."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parents[2]
DESTINATION = PROJECT.parent / '交付文件'
EXCLUDE = {'.git', '.runtime', '.private', 'target', 'dist', 'node_modules', '__pycache__', '.pnpm-store'}
TREES = ('foundation-service', 'provider-service', 'demand-service', 'foundation-web', 'deploy/linux', 'sql/source', 'examples/local-kafka')


def files():
    result = []
    for tree in TREES:
        for folder, directories, names in os.walk(PROJECT / tree):
            directories[:] = [name for name in directories if name not in EXCLUDE]
            for name in names:
                file = Path(folder) / name
                if file.is_symlink() or name.startswith('.env') or file.suffix.lower() in ('.log', '.pem', '.key', '.jks', '.p12', '.pfx', '.pyc'):
                    continue
                result.append(file)
    result += list((PROJECT / 'docs').glob('*.md'))
    result += [PROJECT / name for name in ('pom.xml', 'README.md', '.dockerignore', '.gitattributes', '.gitignore', 'scripts/java/LocalKafka.java', 'scripts/java/kafka-logback.xml')]
    return sorted(set(result))


def main():
    DESTINATION.mkdir(exist_ok=True)
    target = DESTINATION / 'railway-cloud-deploy-20261017.zip'
    # Contains source hashes only, never private configuration or runtime state.
    manifest = {'release': '2026-10-17', 'runtime_validation': 'pending-on-target-server', 'files': {}}
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=PROJECT, capture_output=True, text=True)
    if head.returncode == 0:
        manifest['base_git_commit'] = head.stdout.strip()
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in files():
            relative = file.relative_to(PROJECT).as_posix()
            content = file.read_bytes()
            if relative.startswith('deploy/linux/'):
                content = content.replace(b'\r\n', b'\n')
            manifest['files'][relative] = hashlib.sha256(content).hexdigest()
            archive.writestr('railway-data-foundation/' + relative, content)
        archive.writestr('railway-data-foundation/DEPLOYMENT_RELEASE.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8'))
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.zip.sha256').write_text(f'{digest}  {target.name}\n', encoding='ascii')
    print('Source deployment package built; files:', len(manifest['files']), 'bytes:', target.stat().st_size)
    print('SHA256:', digest)


if __name__ == '__main__':
    main()
