"""Run in a fresh private VPS directory; use deployed code and disposable MySQL schemas."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid

ROOT = Path(__file__).resolve().parent


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def inspect(name):
    return json.loads(command('docker', 'inspect', name))[0]


def sql(db, query):
    return command('docker', 'exec', '-i', db, 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B', input=query)


def snapshot():
    return {name: {'id': (state := inspect(name))['Id'], 'restarts': state['RestartCount'],
                   'started': state['State']['StartedAt']}
            for name in command('docker', 'ps', '--format', '{{.Names}}').splitlines()}


def run_service(service, classes):
    image = inspect(service + '-service')
    db = service + 'db'
    networks = set(image['NetworkSettings']['Networks']) & set(inspect(db)['NetworkSettings']['Networks'])
    if len(networks) != 1:
        raise RuntimeError('Expected exactly one shared database network')
    token = uuid.uuid4().hex
    schema, user = 'recovery_test_' + token, 'rct_' + token[:20]
    password = secrets.token_hex(24)
    env = ROOT / (service + '.env')
    container = 'checkout-recovery-' + token[:12]
    result = {'image_id': image['Image'], 'image_tag': image['Config']['Image'],
              'mysql_version': sql(db, 'SELECT VERSION();'), 'schema': schema}
    try:
        sql(db, f"CREATE DATABASE `{schema}`; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; "
                f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{user}'@'%';")
        env.write_text(f'RECOVERY_TEST_JDBC_URL=jdbc:mysql://{db}:3306/{schema}\n'
                       f'RECOVERY_TEST_JDBC_USER={user}\nRECOVERY_TEST_JDBC_PASSWORD={password}\n')
        env.chmod(0o600)
        mockito = next((ROOT / service / 'lib').glob('mockito-core-*.jar'))
        with (ROOT / (service + '-output.txt')).open('w') as output:
            process = subprocess.run(['docker', 'run', '--rm', '--name', container,
                '--network', next(iter(networks)), '--memory', '768m', '--cpus', '1',
                '--env-file', str(env), '-v', f'{ROOT}:/work:ro', '--entrypoint', 'java',
                image['Image'], '-Xms64m', '-Xmx512m',
                '-javaagent:/work/' + service + '/lib/' + mockito.name,
                '-cp', f'/work/{service}/classes:/app/resources:/app/classes:/app/libs/*:/work/{service}/lib/*',
                'RecoveryTestRunner', *['com.example.' + service + '_service.' + name for name in classes]],
                stdout=output, stderr=subprocess.STDOUT, timeout=600)
        result['exit_code'] = process.returncode
        output = (ROOT / (service + '-output.txt')).read_text()
        result['trials'] = re.findall(r'^RECOVERY_TRIAL (\S+) (\S+) (\d+) recovered$', output, re.MULTILINE)
        result['junit_summary'] = re.findall(r'^RECOVERY_RESULT .+$', output, re.MULTILINE)
    finally:
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        sql(db, f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{user}'@'%';")
        env.unlink(missing_ok=True)
        result['schema_removed'] = sql(db, f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{schema}';") == '0'
        result['test_user_removed'] = sql(db, f"SELECT COUNT(*) FROM mysql.user WHERE User='{user}';") == '0'
        (ROOT / (service + '-result.json')).write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    os.umask(0o077)
    if (ROOT / 'result.json').exists():
        raise RuntimeError('Use a fresh private directory')
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError('Package integrity mismatch: ' + name)
    result = {'started_utc': datetime.now(timezone.utc).isoformat(), 'host': command('hostname'),
              'package_sha256': hashlib.sha256((ROOT / 'checkout-recovery-tests.zip').read_bytes()).hexdigest(),
              'planned_failures': 100, 'before': snapshot(), 'services': {}}
    try:
        for service, classes in manifest['suites'].items():
            result['services'][service] = run_service(service, classes)
    finally:
        result['after'] = snapshot()
        result['service_containers_unchanged'] = result['before'] == result['after']
        result['finished_utc'] = datetime.now(timezone.utc).isoformat()
        trials = [tuple(t) for s in result['services'].values() for t in s.get('trials', [])]
        expected = {(service, fault, str(trial)) for service, faults in {
            'booking': ['seat-response-lost', 'reservation-worker-crashed', 'payment-response-lost'],
            'payment': ['provider-unavailable', 'checkout-write-failed']}.items()
            for fault in faults for trial in range(1, 21)}
        result['recovered_failures'] = len(set(trials) & expected)
        result['recovery_percent'] = result['recovered_failures'] * 100.0 / 100
        result['trials_match_plan'] = set(trials) == expected and len(trials) == 100
        result['scenario_counts'] = dict(Counter(t[1] for t in trials))
        result['passed'] = (result['trials_match_plan'] and result['service_containers_unchanged']
            and len(result['services']) == 2 and all(s.get('exit_code') == 0 and s.get('junit_summary')
                and s['schema_removed'] and s['test_user_removed'] for s in result['services'].values()))
        (ROOT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k not in ('before', 'after', 'services')}, indent=2))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
