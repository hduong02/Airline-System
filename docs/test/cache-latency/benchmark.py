"""Run on the Docker host or a laptop; emit a credential-free JSON report.

Uses Python's standard library, a fixed arrival schedule, and persistent HTTP
connections. Only the selected flightInstances keys are deleted. No FLUSHALL.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import threading
import time
import urllib.parse

SSH_HOST = None


def remote_python(source, timeout=30):
    result = subprocess.run(
        ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', SSH_HOST, 'python3 -'],
        input=source, capture_output=True, text=True, timeout=timeout, check=True)
    return result.stdout.strip()


def redis(*args):
    if SSH_HOST:
        command = ['docker', 'exec', 'redis', 'redis-cli', '--raw', *map(str, args)]
        output = remote_python(
            'import subprocess\n'
            'r = subprocess.run(' + repr(command) + ', capture_output=True, text=True, check=True, timeout=20)\n'
            'print(r.stdout.strip())\n')
        if output.startswith(('ERR ', 'NOAUTH ', 'WRONGTYPE ')):
            raise RuntimeError('Remote Redis command failed')
        return output
    result = subprocess.run(
        ['docker', 'exec', 'redis', 'redis-cli', '--raw', *map(str, args)],
        capture_output=True, text=True, check=True, timeout=20)
    if result.stdout.startswith(('ERR ', 'NOAUTH ', 'WRONGTYPE ')):
        raise RuntimeError('Redis command failed: ' + result.stdout.splitlines()[0])
    return result.stdout.strip()


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)] if ordered else None


def main():
    global SSH_HOST
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:5000')
    parser.add_argument('--credentials', help='Explicitly authorized seeded account file')
    parser.add_argument('--count', type=int, default=1000)
    parser.add_argument('--rps', type=int, default=100)
    parser.add_argument('--start-id', type=int, default=1)
    parser.add_argument('--ssh-host', help='Remote Docker host for cache control and seeded login only; HTTP load stays local')
    args = parser.parse_args()
    SSH_HOST = args.ssh_host
    if not 1 <= args.count <= 6000 or not 1 <= args.rps <= 100 or args.start_id < 1:
        raise ValueError('Bounds: 1–6000 IDs, 1–100 RPS, positive start ID')
    base = urllib.parse.urlsplit(args.base_url)
    if base.scheme != 'http':
        raise ValueError('This harness expects HTTP')
    origin = base.path.rstrip('/')
    local = threading.local()
    connections = []
    token = os.environ.get('BENCHMARK_TOKEN')
    if not token:
        if not args.credentials:
            raise ValueError('Supply BENCHMARK_TOKEN or explicitly authorized --credentials')
        if SSH_HOST:
            # Only the short-lived token crosses SSH into captured process memory.
            # The approved account password stays on the server.
            source = (
                'import json, urllib.request\n'
                'from pathlib import Path\n'
                'credentials=json.loads(Path(' + repr(args.credentials) + ').read_text())\n'
                'c=credentials[0] if isinstance(credentials,list) else credentials\n'
                'req=urllib.request.Request("http://127.0.0.1:5000/auth/login", '
                'data=json.dumps({k:c[k] for k in ("email","password")}).encode(), '
                'headers={"Content-Type":"application/json"})\n'
                'with urllib.request.urlopen(req,timeout=20) as r: payload=json.load(r)\n'
                'assert payload.get("jwt"), "Login returned no token"\n'
                'print(payload["jwt"])\n')
            token = remote_python(source)
        else:
            credentials = json.loads(Path(args.credentials).read_text())
            credential = credentials[0] if isinstance(credentials, list) else credentials
            connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=20)
            connection.request('POST', origin + '/auth/login',
                               json.dumps({k: credential[k] for k in ('email', 'password')}),
                               {'Content-Type': 'application/json'})
            response = connection.getresponse()
            payload = json.loads(response.read())
            connection.close()
            if response.status != 200 or not payload.get('jwt'):
                raise RuntimeError('Benchmark login failed; status ' + str(response.status))
            token = payload['jwt']
            del credentials, credential, payload

    ids = list(range(args.start_id, args.start_id + args.count))
    keys = ['flightInstances::' + str(i) for i in ids]
    def exists():
        return int(redis('EXISTS', *keys))

    def request(instance_id, due):
        started = time.perf_counter()
        result = {'id': instance_id, 'start_lag_ms': (started - due) * 1000}
        try:
            if not hasattr(local, 'connection'):
                local.connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=60)
                connections.append(local.connection)
            local.connection.request('GET', origin + '/api/flight-instances/' + str(instance_id),
                                     headers={'Authorization': 'Bearer ' + token})
            response = local.connection.getresponse()
            body = response.read()
            result['latency_ms'] = (time.perf_counter() - started) * 1000
            result['status'] = response.status
            parsed = json.loads(body) if response.status == 200 else None
            result['valid'] = isinstance(parsed, dict) and parsed.get('id') == instance_id
            if result['valid']:
                result['response_sha256'] = hashlib.sha256(
                    json.dumps(parsed, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        except Exception as exc:
            result.update(latency_ms=(time.perf_counter() - started) * 1000,
                          valid=False, error=type(exc).__name__)
            if hasattr(local, 'connection'):
                local.connection.close()
                del local.connection
        return result

    def phase(pool, name):
        print('Starting ' + name, file=__import__('sys').stderr, flush=True)
        start = time.perf_counter()
        futures = []
        for index, instance_id in enumerate(ids):
            due = start + index / args.rps
            time.sleep(max(0, due - time.perf_counter()))
            futures.append(pool.submit(request, instance_id, due))
        rows = [f.result() for f in futures]
        elapsed = time.perf_counter() - start
        valid = [r['latency_ms'] for r in rows if r['valid']]
        lags = [r['start_lag_ms'] for r in rows]
        summary = {
            'requests': len(rows), 'successful': len(valid),
            'errors': len(rows) - len(valid), 'elapsed_seconds': elapsed,
            'achieved_completion_rps': len(rows) / elapsed,
            'target_arrival_rps': args.rps,
            'latency_ms_success_only': {
                'mean': statistics.mean(valid) if valid else None,
                'p50': percentile(valid, 50), 'p95': percentile(valid, 95),
                'p99': percentile(valid, 99), 'max': max(valid) if valid else None},
            'start_lag_ms': {'p95': percentile(lags, 95), 'max': max(lags)},
            'selected_keys_present_after': exists(),
        }
        start_offsets = [index / args.rps + row['start_lag_ms'] / 1000
                         for index, row in enumerate(rows)]
        span = max(start_offsets) - min(start_offsets)
        summary['actual_start_rps'] = (len(rows) - 1) / span if span > 0 else None
        summary['arrival_window_seconds'] = args.count / args.rps
        summary['ten_second_arrival_groups'] = []
        for offset in range(0, len(rows), args.rps * 10):
            group = rows[offset:offset + args.rps * 10]
            latencies = [r['latency_ms'] for r in group if r['valid']]
            summary['ten_second_arrival_groups'].append({
                'scheduled_start_second': offset / args.rps,
                'requests': len(group), 'errors': sum(not r['valid'] for r in group),
                'mean_ms': statistics.mean(latencies) if latencies else None,
                'p95_ms': percentile(latencies, 95),
                'p95_start_lag_ms': percentile([r['start_lag_ms'] for r in group], 95),
            })
        return {'summary': summary, 'requests': rows}

    # Connectivity/correctness probe is removed before the cold measurement.
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.count, 1000)) as pool:
        probe = pool.submit(request, ids[0], time.perf_counter()).result()
        if not probe['valid']:
            raise RuntimeError('Endpoint preflight failed: ' + str(probe.get('status', probe.get('error'))))
        before = exists()
        deleted = int(redis('DEL', *keys))
        if exists() != 0:
            raise RuntimeError('Selected cache entries are not empty before cold pass')
        report = {
            'started_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'base_url': args.base_url, 'endpoint': '/api/flight-instances/{id}',
            'runner': ('Python stdlib on laptop, direct HTTP to remote gateway; SSH for cache control/login only'
                       if SSH_HOST else 'Python stdlib on API Docker host, through gateway loopback'),
            'cpu_count': os.cpu_count(), 'workers': min(args.count, 1000),
            'ids': {'first': ids[0], 'last': ids[-1], 'count': len(ids)},
            'cache': {'name': 'flightInstances', 'ttl_seconds_configured': 180,
                      'selected_keys_before_clear': before, 'deleted': deleted,
                      'selected_keys_before_cold': 0},
        }
        cold_start = time.perf_counter()
        report['cold'] = phase(pool, 'cold')
        if report['cold']['summary']['errors'] or exists() != len(ids):
            report['warm_skipped_reason'] = 'Cold errors or selected cache entries missing'
        elif time.perf_counter() - cold_start + args.count / args.rps + 15 >= 180:
            report['warm_skipped_reason'] = 'Insufficient TTL margin for paired pass'
        else:
            report['cache']['min_ttl_before_warm_seconds'] = min(
                int(redis('TTL', keys[0])), int(redis('TTL', keys[-1])))
            report['warm'] = phase(pool, 'warm')
            cold_hashes = {r['id']: r.get('response_sha256') for r in report['cold']['requests']}
            report['response_mismatches'] = sum(
                not r['valid'] or r.get('response_sha256') != cold_hashes[r['id']]
                for r in report['warm']['requests'])
            report['cache']['first_key_ttl_after_warm_seconds'] = int(redis('TTL', keys[0]))
        report['finished_at_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for connection in connections:
        connection.close()
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
