"""Run inside a private VPS staging directory. Never modifies the application schema."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def inspect(name):
    return json.loads(command('docker', 'inspect', name))[0]


def sql(query):
    # Read the existing root password only inside the DB container, never into logs.
    return command('docker', 'exec', '-i', 'seatdb', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B', input=query)


def snapshot():
    names = command('docker', 'ps', '--format', '{{.Names}}').splitlines()
    return {name: {'id': (state := inspect(name))['Id'],
                   'restarts': state['RestartCount'], 'started': state['State']['StartedAt']}
            for name in names}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--attempts', type=int, default=100)
    args = parser.parse_args()
    if not 2 <= args.attempts <= 128:
        parser.error('attempts must be between 2 and 128')
    os.umask(0o077)
    root = Path(__file__).resolve().parent
    result_file = root / 'result.json'
    if result_file.exists():
        raise RuntimeError('Use a fresh staging directory for each run')
    seat = inspect('seat-service')
    db = inspect('seatdb')
    networks = set(seat['NetworkSettings']['Networks']) & set(db['NetworkSettings']['Networks'])
    if len(networks) != 1:
        raise RuntimeError('Expected one shared seat-service/seatdb Docker network')
    max_connections, connected = map(int, sql('SELECT @@max_connections, '
            "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected');").split())
    if max_connections - connected < args.attempts + 20:
        raise RuntimeError(f'Insufficient MySQL connection headroom: {connected}/{max_connections}')
    token = uuid.uuid4().hex
    schema = 'seat_inventory_test_' + token
    user = 'sit_' + token[:20]
    password = secrets.token_hex(24)
    container = 'seat-inventory-test-' + token[:12]
    environment_file = root / 'test.env'
    result = {'started_utc': datetime.now(timezone.utc).isoformat(), 'attempts_per_scenario': args.attempts,
              'host': command('hostname'), 'image_id': seat['Image'], 'image_tag': seat['Config']['Image'],
              'mysql_version': sql('SELECT VERSION();'), 'mysql_connections_before': connected,
              'mysql_max_connections': max_connections, 'schema': schema,
              'package_sha256': hashlib.sha256((root / 'seat-inventory-tests.zip').read_bytes()).hexdigest(),
              'before': snapshot()}
    try:
        sql(f"CREATE DATABASE `{schema}`; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; "
            f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{user}'@'%';")
        environment_file.write_text(f'SEAT_INVENTORY_TEST_JDBC_URL=jdbc:mysql://seatdb:3306/{schema}\n'
                f'SEAT_INVENTORY_TEST_JDBC_USER={user}\nSEAT_INVENTORY_TEST_JDBC_PASSWORD={password}\n')
        environment_file.chmod(0o600)
        classpath = '/work/classes:/app/resources:/app/classes:/app/libs/*:/work/lib/*'
        with (root / 'test-output.txt').open('w') as log:
            process = subprocess.run(['docker', 'run', '--rm', '--name', container,
                '--network', next(iter(networks)), '--memory', '768m', '--cpus', '2',
                '--env-file', str(environment_file), '-v', f'{root}:/work:ro',
                '--entrypoint', 'java', seat['Image'], '-Xms128m', '-Xmx512m',
                f'-Dinventory.attempts={args.attempts}', '-cp', classpath, 'InventoryTestRunner'],
                stdout=log, stderr=subprocess.STDOUT, timeout=600)
        result['exit_code'] = process.returncode
        output = (root / 'test-output.txt').read_text()
        result['inventory_scenarios'] = re.findall(r'Seat inventory \[[^\n]+', output)
        result['junit_summary'] = re.findall(r'INVENTORY_RESULT[^\n]+', output)
    finally:
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Names are generated here; cleanup is restricted to this run's UUID schema/account.
        sql(f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{user}'@'%';")
        environment_file.unlink(missing_ok=True)
        result['schema_removed'] = sql(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{schema}';") == '0'
        result['test_user_removed'] = sql(f"SELECT COUNT(*) FROM mysql.user WHERE User='{user}';") == '0'
        result['after'] = snapshot()
        result['service_containers_unchanged'] = result['before'] == result['after']
        result['finished_utc'] = datetime.now(timezone.utc).isoformat()
        result_file.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: value for key, value in result.items() if key not in ('before', 'after')}, indent=2))
    if (result.get('exit_code') != 0 or len(result.get('inventory_scenarios', [])) != 2
            or not result['service_containers_unchanged']):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
