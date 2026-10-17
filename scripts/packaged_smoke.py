#!/usr/bin/env python3
"""Boot the exact remapped release JAR in a separate Fabric launcher instance."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / 'run-package'
OUT = ROOT / 'artifacts/qa'
JAR = ROOT / 'build/libs/chzzk-donation-api-fabric-1.21.1-0.1.0.jar'
assert (RUN / 'eula.txt').read_text().strip() == 'eula=true'
assert (RUN / 'fabric-server-launch.jar').is_file(), 'Prepare official Fabric launcher first'
shutil.copy2(JAR, RUN / 'mods' / JAR.name)
api = next(Path.home().joinpath('.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.116.17+1.21.1').glob('*/*.jar'))
shutil.copy2(api, RUN / 'mods' / api.name)
log = OUT / 'packaged-jar.log'
with log.open('w') as output:
    proc = subprocess.Popen(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xms512M', '-Xmx1G', '-jar', 'fabric-server-launch.jar', 'nogui'],
                            cwd=RUN, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            data = log.read_text(errors='replace')
            if 'Done (' in data and 'CHZZK Donation API initialized for this server session' in data:
                break
            if proc.poll() is not None:
                raise RuntimeError(data[-5000:])
            time.sleep(1)
        else:
            raise TimeoutError('Packaged server readiness timeout')
        assert proc.stdin is not None
        proc.stdin.write('chzzkdonation status\nchzzkdonation reload\n')
        proc.stdin.flush()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            data = log.read_text(errors='replace')
            if 'CHZZK Donation API config reloaded' in data and 'bindings 0/32' in data:
                break
            time.sleep(0.25)
        else:
            raise TimeoutError('Packaged server command smoke failed')
        proc.stdin.write('stop\n')
        proc.stdin.flush()
        proc.wait(timeout=60)
        assert proc.returncode == 0, proc.returncode
        cfg = json.loads((RUN / 'config/chzzk-donation-api.json').read_text())
        assert cfg == {'version': 1, 'bindings': []}
        assert 'chzzk_donation_qa' not in data
        result = {'passed': True, 'exit_code': proc.returncode, 'artifact': JAR.name,
                  'sha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'qa_mod_present': False,
                  'paid_donation_verified': False}
        (OUT / 'packaged-jar-result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try: proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=10)
