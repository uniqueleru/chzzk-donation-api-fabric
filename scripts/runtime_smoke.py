#!/usr/bin/env python3
"""Run isolated dev-environment dedicated + integrated-server API smoke checks.
No production service is touched. QA-only companion mod validates callbacks.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts' / 'qa'
ENV = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-21-openjdk-amd64', LIBGL_ALWAYS_SOFTWARE='1')
ENV['PATH'] = ENV['JAVA_HOME'] + '/bin:' + ENV['PATH']
PROCESSES = []


def launch(command, name, env=ENV):
    log = OUT / (name + '.log')
    handle = log.open('w')
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=handle,
                               stderr=subprocess.STDOUT, start_new_session=True, text=True)
    PROCESSES.append((process, handle))
    return process, log


def await_text(process, path, text, timeout=300):
    deadline = time.monotonic() + timeout
    data = ''
    while time.monotonic() < deadline:
        data = path.read_text(errors='replace') if path.exists() else ''
        if text in data:
            return data
        if process.poll() is not None:
            raise RuntimeError(f'{path.name} exited {process.returncode} before {text}:\n{data[-4500:]}')
        time.sleep(1)
    raise TimeoutError(f'{path.name}: no {text}:\n{data[-4500:]}')


def stop(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['dedicated', 'integrated', 'both'], default='both')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    # Select a free X display using Xvfb's own display allocator.
    read_fd, write_fd = os.pipe()
    xlog = (OUT / 'xvfb.log').open('w')
    xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0', '1280x720x24', '-nolisten', 'tcp'],
                            pass_fds=(write_fd,), stdout=xlog, stderr=subprocess.STDOUT, start_new_session=True)
    PROCESSES.append((xvfb, xlog))
    os.close(write_fd)
    with os.fdopen(read_fd) as pipe:
        display = pipe.readline().strip()
    if not display:
        raise RuntimeError('Xvfb display allocation failed')
    ENV['DISPLAY'] = ':' + display
    result = {'dedicated': None, 'integrated': None, 'real_paid_donation_verified': False}
    if args.mode in ('dedicated', 'both'):
        server, slog = launch(['bash', 'gradlew', '--no-daemon', 'runQaServer'], 'dedicated')
        await_text(server, slog, 'Done (', timeout=360)
        client, clog = launch(['bash', 'gradlew', '--no-daemon', 'runQaClient', '-PqaJoin'], 'multiplayer-client')
        await_text(server, slog, 'QA_PASS dedicated=true', timeout=420)
        # Close client first, then use normal server save/stop.
        stop(client)
        assert server.stdin is not None
        server.stdin.write('stop\n')
        server.stdin.flush()
        server.wait(timeout=90)
        data = slog.read_text(errors='replace')
        assert 'QA_SERVER_STOPPED passed=true' in data
        assert server.returncode == 0, server.returncode
        result['dedicated'] = {'passed': True, 'exit_code': server.returncode, 'real_events_from_test': 0, 'synthetic_fixture_received_events': 1, 'dedup_offline_rejoin_checks': True}
    if args.mode in ('integrated', 'both'):
        source = ROOT / 'run-qa-server' / 'qa-world'
        target = ROOT / 'run-qa-client' / 'saves' / 'qa-world'
        if not source.is_dir():
            raise RuntimeError('Dedicated world is needed before integrated smoke')
        # Overwrite ONLY this script-owned QA fixture, not a user world.
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
        client, clog = launch(['bash', 'gradlew', '--no-daemon', 'runQaClient'], 'integrated-client')
        await_text(client, clog, 'QA_PASS dedicated=false', timeout=420)
        client.wait(timeout=120)
        data = clog.read_text(errors='replace')
        assert 'QA_SERVER_STOPPED passed=true' in data
        assert client.returncode == 0, client.returncode
        result['integrated'] = {'passed': True, 'exit_code': client.returncode, 'real_events_from_test': 0, 'synthetic_fixture_received_events': 1, 'dedup_offline_rejoin_checks': True}
    (OUT / ('runtime-' + args.mode + '-result.json')).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    finally:
        for proc, log in reversed(PROCESSES):
            stop(proc)
            log.close()
