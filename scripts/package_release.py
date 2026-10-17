#!/usr/bin/env python3
"""Create and verify a self-contained versioned handoff; no runtime state is exported."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line)
VERSION = properties['mod_version']
BASE = properties['archives_base_name'] + '-' + VERSION
OUT = ROOT / 'exports' / ('v' + VERSION)
OUT.mkdir(parents=True, exist_ok=True)

suites = [ET.parse(p).getroot() for p in (ROOT / 'build/test-results/test').glob('TEST-*.xml')]
assert suites and sum(int(x.attrib.get('tests', 0)) for x in suites) > 0
assert all(int(x.attrib.get('failures', 0)) + int(x.attrib.get('errors', 0)) == 0 for x in suites)
runtime = json.loads((ROOT / 'artifacts/qa/runtime-both-result.json').read_text())
assert runtime['dedicated']['passed'] and runtime['integrated']['passed']
assert 'JAVA_DIRECT_HANDSHAKE_PASS' in (ROOT / 'artifacts/qa/upstream-java-probe.log').read_text()
assert 'owned_threads_after_close=0' in (ROOT / 'artifacts/qa/upstream-java-probe.log').read_text()

for suffix in ('.jar', '-sources.jar'):
    src = ROOT / 'build/libs' / (BASE + suffix)
    assert src.is_file()
    shutil.copy2(src, OUT / src.name)

packaged_proof = json.loads((ROOT / 'artifacts/qa/packaged-jar-result.json').read_text())
assert packaged_proof['passed']
assert packaged_proof['sha256'] == hashlib.sha256((OUT / (BASE + '.jar')).read_bytes()).hexdigest()

with zipfile.ZipFile(OUT / (BASE + '.jar')) as jar:
    assert jar.testzip() is None
    metadata = json.loads(jar.read('fabric.mod.json'))
    assert metadata['id'] == 'chzzk_donation_api'
    assert metadata['version'] == VERSION
    assert metadata['environment'] == '*'
    assert metadata['depends']['minecraft'] == '1.21.1'
    assert not any('/qa/' in n or 'PipelineProbe' in n or 'chzzk_donation_qa' in n for n in jar.namelist())
    for name in jar.namelist():
        if name.endswith('.class'):
            assert int.from_bytes(jar.read(name)[6:8], 'big') == 65, name

members = ['README.md', 'LICENSE', 'docs/API.md', 'docs/VERIFICATION.md', 'examples/ChzzkDonationConsumerExample.java']
for name in members:
    target = OUT / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / name, target)

source_names = ['README.md', 'LICENSE', '.gitignore', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat']
for folder in ['src', 'gradle', 'docs', 'examples', 'scripts']:
    source_names += [str(p.relative_to(ROOT)) for p in (ROOT / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc']
source_zip = OUT / (BASE + '-project-source.zip')
with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in sorted(set(source_names)):
        archive.write(ROOT / name, 'chzzk-donation-api/' + name)
with zipfile.ZipFile(source_zip) as archive:
    assert archive.testzip() is None
    for name in archive.namelist():
        source = ROOT / name.removeprefix('chzzk-donation-api/')
        assert archive.read(name) == source.read_bytes()

manifest = []
for path in sorted(OUT.rglob('*')):
    if path.is_file() and path.name not in ['SHA256SUMS.txt', 'manifest.json'] and not path.name.endswith('-bundle.zip'):
        manifest.append({'file': str(path.relative_to(OUT)), 'bytes': path.stat().st_size,
                         'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
(OUT / 'manifest.json').write_text(json.dumps({'version': VERSION, 'files': manifest}, indent=2) + '\n')
(OUT / 'SHA256SUMS.txt').write_text(''.join(f"{entry['sha256']}  {entry['file']}\n" for entry in manifest))
bundle = OUT / (BASE + '-bundle.zip')
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
    for entry in manifest:
        archive.write(OUT / entry['file'], entry['file'])
    archive.write(OUT / 'manifest.json', 'manifest.json')
    archive.write(OUT / 'SHA256SUMS.txt', 'SHA256SUMS.txt')
with zipfile.ZipFile(bundle) as archive:
    assert archive.testzip() is None
    expected = {entry['file'] for entry in manifest} | {'manifest.json', 'SHA256SUMS.txt'}
    assert set(archive.namelist()) == expected
    for entry in manifest:
        assert hashlib.sha256(archive.read(entry['file'])).hexdigest() == entry['sha256']
print(json.dumps({'primary': str(bundle), 'bytes': bundle.stat().st_size,
                  'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(), 'files': manifest}, indent=2))
