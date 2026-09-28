"""VPS setup and read-only inventory; credentials are returned only to the private harness."""
import argparse
import datetime
from email.header import decode_header, make_header
import json
import os
import re
import socket
from pathlib import Path
import subprocess
import time
import urllib.request

STACK = Path('/opt/airline-system')


def cmd(*args):
    return subprocess.check_output(args, text=True).strip()


def inspect(name):
    return json.loads(cmd('docker', 'inspect', name))[0]


def query(container, database, sql):
    output = cmd('docker', 'exec', container, 'sh', '-c',
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B "$1" -e "$2"',
                 'sh', database, sql)
    return [line.split('\t') for line in output.splitlines()]


def compose(root, extra, *args):
    files = ['-f', str(STACK/'docker-compose.yml'), '-f', str(STACK/'docker-compose.override.yml')]
    if extra:
        files += ['-f', str(root/'load-override.json')]
    return cmd('docker', 'compose', '--project-directory', str(STACK), *files, *args)


def health(name, port):
    container = inspect(name)
    ip = next(iter(container['NetworkSettings']['Networks'].values()))['IPAddress']
    with urllib.request.urlopen('http://%s:%s/actuator/health' % (ip, port), timeout=5) as response:
        return json.load(response).get('status') == 'UP'


def wait_health():
    for _ in range(90):
        try:
            if health('payment-service', 5010):
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('Payment health timeout')


def deploy(root):
    os.umask(0o077)
    if (root/'manifest.json').exists():
        raise RuntimeError('Use a new run directory')
    current = inspect('payment-service')
    baseline = {n: {'restarts': inspect(n)['RestartCount'], 'started': inspect(n)['State']['StartedAt']}
                for n in cmd('docker', 'ps', '--format', '{{.Names}}').splitlines()}
    simulator = 'stripe-simulator-'+root.name.replace('booking-mailhog-', '')
    simulator = simulator.lower()
    manifest = {'image': current['Image'], 'baseline': baseline, 'restored': False,
                'simulator_service': simulator,
                'extra_hosts': current['HostConfig'].get('ExtraHosts') or [],
                'environment': dict(e.split('=', 1) for e in current['Config']['Env'])}
    (root/'manifest.json').write_text(json.dumps(manifest, indent=2))
    for name in ['docker-compose.yml', 'docker-compose.override.yml']:
        (root/(name+'.before')).write_bytes((STACK/name).read_bytes())
    # Pin the actual pre-run container environment and image, even if it was
    # launched with an earlier test override absent from the normal Compose files.
    (root/'restore-override.json').write_text(json.dumps({'services': {
        'payment-service': {'image': current['Image'], 'environment': manifest['environment'],
                            'extra_hosts': manifest['extra_hosts']}}}))
    override = {'services': {
        'payment-service': {'image': current['Image'], 'environment': {
            **manifest['environment'],
            'SPRING_PROFILES_ACTIVE': 'load-test', 'STRIPE_API_KEY': 'sk_test_simulator',
            'STRIPE_API_BASE_URL': 'http://stripe-simulator:8080'}},
        simulator: {'image': 'node:22-alpine', 'command': ['node', '/simulator.cjs'],
            'volumes': [str(root/'simulator.cjs')+':/simulator.cjs:ro'],
            'environment': {'SIM_OUTCOME': 'paid', 'SIM_LATENCY_MS': '0'},
            'mem_limit': '128m', 'cpus': 0.25}}}
    (root/'load-override.json').write_text(json.dumps(override))
    try:
        compose(root, True, 'config', '--quiet')
        compose(root, True, 'up', '-d', '--no-deps', '--pull', 'never', simulator)
        sim = inspect(compose(root, True, 'ps', '-q', simulator))
        ip = next(iter(sim['NetworkSettings']['Networks'].values()))['IPAddress']
        for _ in range(30):
            try:
                with urllib.request.urlopen('http://'+ip+':8080/health', timeout=3) as response:
                    if json.load(response)['outcome'] == 'paid':
                        break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Simulator not ready')
        # The application allowlists this exact hostname. Pin it to this run's
        # private container instead of sharing DNS with an existing simulator.
        override['services']['payment-service']['extra_hosts'] = [
            entry for entry in manifest['extra_hosts'] if not entry.startswith('stripe-simulator:')
        ] + ['stripe-simulator:'+ip]
        (root/'load-override.json').write_text(json.dumps(override))
        compose(root, True, 'up', '-d', '--no-deps', '--pull', 'never', 'payment-service')
        wait_health()
        env = inspect('payment-service')['Config']['Env']
        assert 'SPRING_PROFILES_ACTIVE=load-test' in env
        assert 'STRIPE_API_BASE_URL=http://stripe-simulator:8080' in env
        assert 'STRIPE_API_KEY=sk_test_simulator' in env
        assert 'MAIL_HOST=mailhog' in inspect('notification-service')['Config']['Env']
        manifest['baseline']['payment-service'] = {
            'restarts': inspect('payment-service')['RestartCount'],
            'started': inspect('payment-service')['State']['StartedAt']}
        manifest['baseline'][sim['Name'].lstrip('/')] = {
            'restarts': sim['RestartCount'], 'started': sim['State']['StartedAt']}
        (root/'manifest.json').write_text(json.dumps(manifest, indent=2))
        return {'deployed': True, 'baseline': manifest['baseline']}
    except BaseException:
        restore(root)
        raise


def restore(root):
    manifest = json.loads((root/'manifest.json').read_text())
    for name in ['docker-compose.yml', 'docker-compose.override.yml']:
        if (STACK/name).read_bytes() != (root/(name+'.before')).read_bytes():
            raise RuntimeError('Compose configuration changed externally; manual restoration required')
    if (root/'restore-override.json').exists():
        cmd('docker', 'compose', '--project-directory', str(STACK),
            '-f', str(STACK/'docker-compose.yml'), '-f', str(STACK/'docker-compose.override.yml'),
            '-f', str(root/'restore-override.json'), 'up', '-d', '--no-deps', '--pull', 'never', 'payment-service')
    else:
        compose(root, False, 'up', '-d', '--no-deps', '--pull', 'never', 'payment-service')
    wait_health()
    if inspect('payment-service')['Image'] != manifest['image']:
        raise RuntimeError('Restored image differs')
    if 'environment' in manifest and dict(e.split('=', 1) for e in inspect('payment-service')['Config']['Env']) != manifest['environment']:
        raise RuntimeError('Restored environment differs')
    if 'extra_hosts' in manifest and sorted(inspect('payment-service')['HostConfig'].get('ExtraHosts') or []) != sorted(manifest['extra_hosts']):
        raise RuntimeError('Restored host mappings differ')
    sim_id = compose(root, True, 'ps', '-q', manifest.get('simulator_service', 'stripe-simulator'))
    if sim_id:
        cmd('docker', 'stop', sim_id)
    manifest['restored'] = True
    (root/'manifest.json').write_text(json.dumps(manifest, indent=2))
    return {'restored': True, 'healthy': True}


def fixtures():
    flights = query('flightdb', 'airline_flight_db', "SELECT id,flight_id FROM flight_instance WHERE is_active=1 AND status='SCHEDULED' AND available_seats>0 AND departure_date_time>UTC_TIMESTAMP()+INTERVAL 2 DAY AND departure_date_time<UTC_TIMESTAMP()+INTERVAL 20 DAY AND DATEDIFF(departure_date_time,UTC_TIMESTAMP())>=COALESCE(min_advance_booking_days,0) AND (max_advance_booking_days IS NULL OR DATEDIFF(departure_date_time,UTC_TIMESTAMP())<=max_advance_booking_days) ORDER BY departure_date_time,id")
    flight_map = dict(flights)
    if not flights:
        return []
    fares = {(r[0], r[1]): r[2] for r in query('pricingdb', 'airline_pricing_db', "SELECT flight_id,cabin_class_id,MIN(id) FROM fare WHERE cabin_class='ECONOMY' AND current_price>0 GROUP BY flight_id,cabin_class_id")}
    seats = query('seatdb', 'airline_seat_db', "SELECT s.id,c.flight_instance_id,c.cabin_class_id FROM seat_instance s JOIN flight_instance_cabin c ON c.id=s.flight_instance_cabin_id WHERE s.status='AVAILABLE' AND s.is_available=1 AND s.is_booked=0 AND s.booking_id IS NULL AND c.flight_instance_id IN ("+','.join(flight_map)+") ORDER BY s.id LIMIT 220000")
    return [dict(seatInstanceId=int(s), flightInstanceId=int(i), flightId=int(flight_map[i]), fareId=int(fares[(flight_map[i], c)]))
            for s, i, c in seats if (flight_map[i], c) in fares]


def verify(root):
    manifest = json.loads((root/'manifest.json').read_text())
    current = inspect('payment-service')
    simulator = compose(root, True, 'ps', '-a', '-q', manifest.get('simulator_service', 'stripe-simulator'))
    return {'restored': manifest.get('restored', False), 'healthy': health('payment-service', 5010),
            'image_matches': current['Image'] == manifest['image'],
            'environment_matches': dict(e.split('=', 1) for e in current['Config']['Env']) == manifest.get('environment'),
            'simulator_stopped': not simulator or not inspect(simulator)['State']['Running']}


def accounts():
    records = json.loads(Path('/root/future-seed-20261015/credentials.json').read_text())
    traveler = next(r for r in records if r['label'] == 'travelers')
    emails = query('userdb', 'airline_user', "SELECT email FROM users WHERE email LIKE 'seed.traveler.%@example.invalid' ORDER BY email LIMIT 160")
    return {'emails': [r[0] for r in emails], 'password': traveler['password']}


def snapshot(root):
    manifest = json.loads((root/'manifest.json').read_text())
    states = {}
    containers = json.loads(cmd('docker', 'inspect', *manifest['baseline']))
    by_name = {c['Name'].lstrip('/'): c for c in containers}
    for name, baseline in manifest['baseline'].items():
        c = by_name[name]
        states[name] = {'running': c['State']['Running'], 'restarts': c['RestartCount'],
                        'started': c['State']['StartedAt'], 'oom': c['State'].get('OOMKilled'),
                        'unchanged': c['RestartCount'] == baseline['restarts'] and c['State']['StartedAt'] == baseline['started']}
    stats = [json.loads(line) for line in cmd('docker', 'stats', '--no-stream', '--format', '{{json .}}').splitlines()]
    return {'time': time.time(), 'states': states, 'stats': stats,
            'meminfo': Path('/proc/meminfo').read_text(), 'loadavg': Path('/proc/loadavg').read_text(),
            'cpu_ticks': Path('/proc/stat').read_text().splitlines()[0],
            'gatewayHealthy': gateway_reachable()}


def mail(root, after=0):
    """Paginate locally; transfer metadata rather than full MIME bodies over SSH."""
    cache = root/'mail-metadata.json'
    records = json.loads(cache.read_text()) if cache.exists() else {}
    seen = set(records)
    offset = 0
    while True:
        with urllib.request.urlopen('http://127.0.0.1:8025/api/v2/messages?start=%d&limit=500' % offset,
                                    timeout=10) as response:
            page = json.load(response)
        items = page.get('items', [])
        known_page = all(item['ID'] in seen for item in items)
        for item in items:
            if item['ID'] in seen:
                continue
            subject = str(make_header(decode_header(' '.join(item.get('Content', {}).get('Headers', {}).get('Subject', [])))))
            match = re.search(r'Booking Confirmed\s*\|\s*([^|]+)', subject)
            created = re.sub(r'\.(\d+)', lambda m: '.'+m.group(1)[:6].ljust(6, '0'), item['Created']).replace('Z', '+00:00')
            if item['ID'] not in records:
                records[item['ID']] = {'id': item['ID'], 'reference': match.group(1).strip() if match else None,
                    'time': datetime.datetime.fromisoformat(created).timestamp(), 'sequence': len(records)+1}
        if not items or known_page or offset+len(items) >= page.get('total', 0):
            break
        offset += max(1, len(items)-50)
    # Client advances its cursor only after journaling the response. Lost SSH
    # responses are replayable, including multiple consecutive failures.
    cache.write_text(json.dumps(records))
    return [record for record in records.values() if record['sequence'] > after]


def gateway_reachable():
    with socket.create_connection(('127.0.0.1', 5000), timeout=3):
        return True


def watch(root, samples=0):
    """Sample on the server over one SSH connection, avoiding handshake jitter."""
    count = 0
    while samples == 0 or count < samples:
        started = time.monotonic()
        print(json.dumps(snapshot(root)), flush=True)
        count += 1
        if samples == 0 or count < samples:
            time.sleep(max(0, 5-(time.monotonic()-started)))


def settle(root):
    """Recover only this run's idempotency keys, including timed-out booking responses."""
    env = inspect('payment-service')['Config']['Env']
    assert 'SPRING_PROFILES_ACTIVE=load-test' in env
    attempts = json.loads((root/'attempts.json').read_text())
    assert all(re.fullmatch(r'[a-f0-9-]{36}', key) for key in attempts)
    rows = []
    for offset in range(0, len(attempts), 500):
        keys = ','.join("'"+key+"'" for key in attempts[offset:offset+500])
        rows += query('bookingdb', 'airline_booking_db',
                      'SELECT a.booking_id,a.user_id,a.idempotency_key,b.booking_reference FROM booking_attempt a JOIN booking b ON b.id=a.booking_id WHERE a.booking_id IS NOT NULL AND a.idempotency_key IN ('+keys+')')
    result = []
    pending = set()
    for offset in range(0, len(rows), 500):
        ids = ','.join(r[0] for r in rows[offset:offset+500])
        pending.update(r[0] for r in query('paymentdb', 'airline_payment_db',
            "SELECT booking_id FROM payment WHERE status='PENDING' AND checkout_session_id LIKE 'cs_sim_%' AND booking_id IN ("+ids+")"))
    ip = next(iter(inspect('payment-service')['NetworkSettings']['Networks'].values()))['IPAddress']
    for bid, uid, attempt, reference in rows:
        if bid in pending:
            request = urllib.request.Request('http://'+ip+':5010/api/payments/verify',
                data=json.dumps({'bookingId': int(bid)}).encode(),
                headers={'Content-Type': 'application/json', 'X-User-Id': uid})
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    result.append({'booking_id': int(bid), 'status': json.load(response)['status']})
            except Exception as exc:
                result.append({'booking_id': int(bid), 'error': str(exc)})
    return {'scoped_bookings': len(rows), 'recovered': result,
            'bookings': [{'booking_id': int(bid), 'attempt': attempt, 'reference': reference}
                         for bid, uid, attempt, reference in rows]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['probe', 'fixtures', 'accounts', 'deploy', 'restore', 'snapshot', 'settle', 'mail', 'verify', 'watch'])
    parser.add_argument('--after', type=int, default=0)
    parser.add_argument('--samples', type=int, default=0)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    modes = {'probe': lambda: {'accounts_keys': list(accounts()), 'fixture_count': len(fixtures())},
             'fixtures': fixtures, 'accounts': accounts, 'deploy': lambda: deploy(root),
             'restore': lambda: restore(root), 'snapshot': lambda: snapshot(root), 'settle': lambda: settle(root),
             'mail': lambda: mail(root, args.after), 'verify': lambda: verify(root)}
    if args.mode == 'watch':
        watch(root, args.samples)
    else:
        print(json.dumps(modes[args.mode]()))
