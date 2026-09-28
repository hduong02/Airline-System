"""Closed-loop booking load via SSH. Workers never poll booking state or email.

Only Python's standard library is required. See README.md for recovery.
"""
import argparse
import concurrent.futures
import datetime as dt
from email.header import decode_header, make_header
import http.client
import json
import math
import os
from pathlib import Path
import queue
import re
import socket
import subprocess
import threading
import time
import uuid

HERE = Path(__file__).resolve().parent


def select_account_emails(emails, max_vus, account_count=None):
    required = max_vus if account_count is None else account_count
    if required <= 0 or len(emails) < required:
        raise RuntimeError('Not enough seeded traveler accounts for requested account count')
    return emails if account_count is None else emails[:account_count]


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    return round(values[max(0, math.ceil(len(values)*p)-1)], 2)


def mail_record(item):
    headers = item.get('Content', {}).get('Headers', {})
    subject = str(make_header(decode_header(' '.join(headers.get('Subject', [])))))
    match = re.search(r'Booking Confirmed\s*\|\s*([^|]+)', subject)
    created = item.get('Created', '')
    # MailHog uses RFC3339 with nanoseconds; datetime accepts microseconds.
    created = re.sub(r'\.(\d+)', lambda m: '.'+m.group(1)[:6].ljust(6, '0'), created).replace('Z', '+00:00')
    return {'id': item['ID'], 'reference': match.group(1).strip() if match else None,
            'time': dt.datetime.fromisoformat(created).timestamp()}


class Runner:
    def __init__(self, args):
        self.args = args
        self.run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        self.remote_dir = '/root/booking-mailhog-'+self.run_id
        self.out = HERE/'results'/self.run_id
        self.out.mkdir(parents=True)
        self.private = HERE/'private'/self.run_id
        self.private.mkdir(parents=True)
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.collector_stop = threading.Event()
        self.requests = []
        self.flows = []
        self.mails = {}
        self.baseline_mail = set()
        self.stages = []
        self.active = 0
        self.stage = 'smoke'
        self.inventory = queue.Queue()
        self.abort_reason = None
        self.tunnel = None
        self.deployed = False
        self.logs = {name: (self.out/(name+'.jsonl')).open('a', encoding='utf-8')
                     for name in ['requests', 'flows', 'resources', 'mail', 'errors']}

    def emit(self, name, value):
        with self.lock:
            self.logs[name].write(json.dumps(value)+'\n')
            self.logs[name].flush()

    def ssh(self, command, timeout=60):
        p = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                            self.args.host, command], capture_output=True, text=True, timeout=timeout)
        if p.returncode:
            raise RuntimeError('SSH failed: '+p.stderr[-1000:])
        return p.stdout.strip()

    def remote(self, mode, timeout=60):
        output = self.ssh('python3 '+self.remote_dir+'/remote.py '+mode, timeout)
        return json.loads(output.splitlines()[-1])

    def abort(self, reason):
        if not self.stop.is_set():
            self.abort_reason = reason
            print('STOP: '+reason, flush=True)
            self.emit('errors', {'time': time.time(), 'fatal': reason})
            self.stop.set()

    def http(self, method, path, body=None, token=None, key=None, measured=False, origin=None, port=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer '+token
        if key:
            headers['Idempotency-Key'] = key
        start = time.perf_counter()
        conn = http.client.HTTPConnection('127.0.0.1', port or self.args.gateway_port, timeout=30)
        status = 0
        error = None
        value = {}
        try:
            conn.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = conn.getresponse()
            status = response.status
            data = response.read()
            value = json.loads(data) if data else {}
            if status >= 400:
                error = 'HTTP '+str(status)
        except Exception as exc:
            error = type(exc).__name__+': '+str(exc)
        finally:
            conn.close()
        record = {'time': time.time(), 'endpoint': path, 'method': method, 'status': status,
                  'latency_ms': (time.perf_counter()-start)*1000, 'origin_stage': origin, 'error': error}
        if measured:
            with self.lock:
                self.requests.append(record)
            self.emit('requests', record)
            if error:
                self.emit('errors', record)
        if error:
            raise RuntimeError(error)
        return value

    def collect_mail(self):
        # Page newest-first and overlap pages when arrivals shift offsets. IDs deduplicate.
        records = {}
        offset = 0
        while True:
            data = self.http('GET', '/api/v2/messages?start=%d&limit=500' % offset,
                             port=self.args.mail_port)
            items = data.get('items', [])
            for item in items:
                record = mail_record(item)
                records[record['id']] = record
            if not items or offset+len(items) >= data.get('total', 0):
                break
            if all(item['ID'] in self.mails or item['ID'] in self.baseline_mail for item in items):
                break
            offset += max(1, len(items)-50)
        for mid, record in records.items():
            if mid not in self.mails and mid not in self.baseline_mail:
                record['observed_at'] = time.time()
                self.mails[mid] = record
                self.emit('mail', record)
        return records

    def collector(self):
        failures = 0
        while not self.collector_stop.is_set():
            start = time.monotonic()
            try:
                self.collect_mail()
                snap = self.remote('snapshot', timeout=25)
                snap['generator'] = {'cpu_seconds': time.process_time(), 'threads': threading.active_count(),
                                     'active_flows': self.active, 'time': time.time()}
                self.emit('resources', snap)
                if not snap['gatewayHealthy']:
                    self.abort('Gateway unhealthy')
                for name, state in snap['states'].items():
                    if not state['running'] or state['oom'] or not state['unchanged']:
                        self.abort('Container changed or failed: '+name)
                failures = 0
            except Exception as exc:
                failures += 1
                self.emit('errors', {'time': time.time(), 'collector': str(exc)})
                if failures >= 3:
                    self.abort('Monitoring failed three consecutive times')
            self.collector_stop.wait(max(0, 5-(time.monotonic()-start)))

    def flow(self, worker, measured=True):
        origin = self.stage
        try:
            fixture = self.inventory.get_nowait()
        except queue.Empty:
            self.abort('Unique eligible seat inventory exhausted')
            return
        account = self.accounts[worker % len(self.accounts)]
        attempt = str(uuid.uuid4())
        record = {'attempt': attempt, 'origin_stage': origin, 'started': time.time(),
                  'seat_id': fixture['seatInstanceId'], 'measured': measured, 'paid': False}
        with self.lock:
            self.active += 1
        try:
            body = {k: fixture[k] for k in ['flightId', 'flightInstanceId', 'fareId']}
            body.update({'cabinClass': 'ECONOMY', 'ancillaryIds': [], 'mealIds': [],
                'contactInfo': {'email': account['email'], 'phone': '+66000000000'},
                'passengers': [{'firstName': 'Load', 'lastName': 'Traveler', 'email': account['email'],
                    'phone': '+66000000000', 'dateOfBirth': '1990-01-01', 'gender': 'MALE',
                    'nationality': 'TH', 'seatInstanceId': fixture['seatInstanceId']}]})
            self.owners[attempt] = worker % len(self.accounts)
            booking = self.http('POST', '/api/bookings', body, account['jwt'],
                                attempt, measured, origin)
            record['booking_id'] = booking['bookingId']
            match = re.search(r'booking reference:\s*(\S+)', booking.get('description', ''), re.I)
            if not match:
                raise RuntimeError('Booking response missing booking reference')
            record['reference'] = match.group(1)
            payment = self.http('POST', '/api/payments/verify', {'bookingId': record['booking_id']},
                                account['jwt'], measured=measured, origin=origin)
            if payment.get('status') != 'SUCCESS':
                raise RuntimeError('Payment status: '+str(payment.get('status')))
            record['paid'] = True
            record['paid_at'] = time.time()
        except Exception as exc:
            record['error'] = str(exc)
            self.emit('errors', {'time': time.time(), 'attempt': attempt, 'flow_error': str(exc)})
        finally:
            record['ended'] = time.time()
            with self.lock:
                self.active -= 1
                self.flows.append(record)
            self.emit('flows', record)
        return record

    def worker(self, index, origin_deadline):
        try:
            while not self.stop.is_set() and time.monotonic() < origin_deadline:
                self.flow(index)
        except BaseException as exc:
            self.abort('Worker failed: '+str(exc))

    def setup(self):
        for port in [self.args.gateway_port, self.args.mail_port]:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', port))
        self.ssh('mkdir -m 700 '+self.remote_dir)
        subprocess.run(['scp', str(HERE/'remote.py'), str(HERE/'simulator.cjs'),
                        self.args.host+':'+self.remote_dir+'/'], check=True, capture_output=True)
        self.tunnel_log = (self.private/'tunnel.log').open('w')
        self.tunnel = subprocess.Popen(['ssh', '-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
            '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
            '-L', '127.0.0.1:%d:127.0.0.1:5000' % self.args.gateway_port,
            '-L', '127.0.0.1:%d:127.0.0.1:8025' % self.args.mail_port,
            self.args.host], stdout=self.tunnel_log, stderr=self.tunnel_log)
        for _ in range(40):
            if self.tunnel.poll() is not None:
                raise RuntimeError('SSH tunnel exited; see private tunnel log')
            try:
                self.http('GET', '/api/v2/messages?limit=1', port=self.args.mail_port)
                with socket.create_connection(('127.0.0.1', self.args.gateway_port), timeout=3):
                    pass
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise RuntimeError('Gateway tunnel not ready')
        fixtures = self.remote('fixtures')
        for fixture in fixtures:
            self.inventory.put(fixture)
        print('Eligible unique seats:', len(fixtures), flush=True)
        credentials = self.remote('accounts')
        emails = select_account_emails(credentials['emails'], max(self.args.vus),
                                       getattr(self.args, 'account_count', None))
        def login(email):
            data = self.http('POST', '/auth/login', {'email': email, 'password': credentials['password']})
            return {'email': email, 'jwt': data['jwt']}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            self.accounts = list(pool.map(login, emails))
        print('Authenticated travelers:', len(self.accounts), flush=True)
        self.baseline_mail = set(self.collect_mail())
        self.mails.clear()
        # Deployment manifests also permit recovery if this process is interrupted.
        self.deployed = True
        print('Deploy:', self.remote('deploy', timeout=300), flush=True)
        (self.out/'run.json').write_text(json.dumps({'run_id': self.run_id, 'host': self.args.host,
            'remote_dir': self.remote_dir, 'vus': self.args.vus, 'stage_seconds': self.args.seconds,
            'seats_available': len(fixtures), 'accounts': len(self.accounts),
            'baseline_mail_count': len(self.baseline_mail)}, indent=2))
        # Allow Eureka and gateway routing to settle after payment-service recreation.
        print('Waiting 90 seconds for Eureka registration and consumer cache refresh', flush=True)
        time.sleep(90)

    def settle(self):
        path = self.out/'attempts.json'
        path.write_text(json.dumps([f['attempt'] for f in self.flows]))
        subprocess.run(['scp', str(path), self.args.host+':'+self.remote_dir+'/attempts.json'],
                       check=True, capture_output=True)
        results = self.remote('settle', timeout=300)
        (self.out/'recovery.json').write_text(json.dumps(results, indent=2))

    def report(self):
        measured = [f for f in self.flows if f['measured']]
        refs = {f['reference']: f for f in measured if f.get('reference')}
        matched = [m for m in self.mails.values() if m.get('reference') in refs]
        counts = {}
        for m in matched:
            counts[m['reference']] = counts.get(m['reference'], 0)+1
        paid = [f for f in measured if f['paid']]
        missing = [f['reference'] for f in paid if counts.get(f.get('reference'), 0) == 0]
        duplicates = {ref: count for ref, count in counts.items() if count > 1}
        end = self.stages[-1]['end'] if self.stages else time.time()
        rows = []
        for stage in self.stages:
            start, finish = stage['start'], stage['end']
            duration = finish-start
            req = [r for r in self.requests if start <= r['time'] < finish]
            completed = [f for f in paid if start <= f['paid_at'] < finish]
            mail = [m for m in matched if start <= m['time'] < finish]
            started = [f for f in measured if f['origin_stage'] == stage['vus']]
            errors = {}
            for r in req:
                if r['error']:
                    key = r['endpoint']+' '+str(r['status'])
                    errors[key] = errors.get(key, 0)+1
            rows.append(dict(stage, duration=duration, requests=len(req), http_rps=len(req)/duration,
                payments_per_sec=len(completed)/duration, emails_per_sec=len(mail)/duration,
                paid=len(completed), emails=len(mail), errors=errors,
                request_p50_ms=percentile([r['latency_ms'] for r in req], .50),
                request_p95_ms=percentile([r['latency_ms'] for r in req], .95),
                request_p99_ms=percentile([r['latency_ms'] for r in req], .99),
                flow_p95_ms=percentile([(f['ended']-f['started'])*1000 for f in started], .95)))
        result = {'stages': rows, 'attempts': len(measured), 'successful_payments': len(paid),
            'matching_messages': len(matched), 'unique_bookings_emailed': len(counts),
            'duplicates': duplicates, 'missing_paid_references': missing,
            'emails_for_non_successful_flows': [m['reference'] for m in matched if not refs[m['reference']]['paid']],
            'late_messages': len([m for m in matched if m['time'] >= end]),
            'unrelated_or_smoke_messages': len(self.mails)-len(matched), 'abort_reason': self.abort_reason,
            'restoration': getattr(self, 'restoration', None)}
        (self.out/'summary.json').write_text(json.dumps(result, indent=2))
        lines = ['# Booking load test '+self.run_id, '',
            'Local Python generator over SSH; booking and payment verification only. No booking polling.', '',
            '| VUs | Seconds | HTTP RPS | Payments/s | Emails/s | p50 ms | p95 ms | p99 ms | Endpoint failures |',
            '|---:|---:|---:|---:|---:|---:|---:|---:|---|']
        for row in rows:
            lines.append('| %d | %.1f | %.2f | %.2f | %.2f | %s | %s | %s | %s |' % (
                row['vus'], row['duration'], row['http_rps'], row['payments_per_sec'], row['emails_per_sec'],
                row['request_p50_ms'], row['request_p95_ms'], row['request_p99_ms'], json.dumps(row['errors'])))
        lines += ['', 'Attempts: %d. Successful payments: %d.' % (len(measured), len(paid)),
                  'Matching emails: %d; unique bookings emailed: %d; missing: %d; duplicate references: %d.' %
                  (len(matched), len(counts), len(missing), len(duplicates)),
                  'Late emails after measured stages: %d.' % result['late_messages'],
                  'Stop reason: '+str(self.abort_reason), 'Restoration: '+str(result['restoration']), '',
                  'Email counts indicate confirmation delivery; ticket counts were not independently checked.',
                  'Throughput includes the SSH network path. Resources are in resources.jsonl; no latency SLO was imposed.']
        (self.out/'REPORT.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
        print(json.dumps(result), flush=True)

    def run(self):
        collector = None
        workers = []
        self.owners = {}
        try:
            self.setup()
            smoke = self.flow(0, measured=False)
            if not smoke or not smoke['paid']:
                raise RuntimeError('Smoke flow failed: '+str(smoke))
            deadline = time.monotonic()+120
            while time.monotonic() < deadline:
                self.collect_mail()
                if any(m['reference'] == smoke['reference'] for m in self.mails.values()):
                    break
                time.sleep(2)
            else:
                raise RuntimeError('Smoke email did not arrive')
            print('Smoke booking and confirmation email passed', flush=True)
            collector = threading.Thread(target=self.collector, daemon=True)
            collector.start()
            # Existing workers continue across boundaries; add workers when VUs double.
            finish = time.monotonic()+len(self.args.vus)*self.args.seconds
            for vus in self.args.vus:
                if self.stop.is_set():
                    break
                self.stage = vus
                stage = {'vus': vus, 'start': time.time()}
                self.stages.append(stage)
                print('STAGE %d VUs START %s' % (vus, dt.datetime.now().isoformat()), flush=True)
                for index in range(len(workers), vus):
                    thread = threading.Thread(target=self.worker, args=(index, finish))
                    workers.append(thread)
                    thread.start()
                deadline = time.monotonic()+self.args.seconds
                while not self.stop.is_set() and time.monotonic() < deadline:
                    time.sleep(.25)
                stage['end'] = time.time()
                print('STAGE %d END requests=%d paid=%d active=%d' % (
                    vus, len(self.requests), sum(f['paid'] for f in self.flows if f['measured']), self.active), flush=True)
            self.stop.set()
            for thread in workers:
                thread.join()
            self.settle()
            deadline = time.monotonic()+120
            while time.monotonic() < deadline:
                paid_refs = {f['reference'] for f in self.flows if f['paid'] and f.get('reference')}
                mailed = {m['reference'] for m in list(self.mails.values())}
                if paid_refs <= mailed:
                    # One final collection window catches immediate duplicates.
                    time.sleep(5)
                    self.collect_mail()
                    break
                time.sleep(2)
        except BaseException as exc:
            self.abort(type(exc).__name__+': '+str(exc))
        finally:
            self.stop.set()
            for thread in workers:
                thread.join()
            self.collector_stop.set()
            if collector:
                collector.join(timeout=40)
            if self.deployed:
                if self.flows and not (self.out/'recovery.json').exists():
                    try:
                        self.settle()
                    except Exception as exc:
                        self.emit('errors', {'time': time.time(), 'recovery_error': str(exc)})
                try:
                    self.restoration = self.remote('restore', timeout=300)
                except Exception as exc:
                    self.restoration = {'error': str(exc), 'manual_recovery_directory': self.remote_dir}
                    print('RESTORATION FAILED: '+str(exc), flush=True)
            if self.tunnel:
                self.tunnel.terminate()
                self.tunnel.wait(timeout=15)
                self.tunnel_log.close()
            # Retain incomplete stages in reports if interrupted.
            for stage in self.stages:
                stage.setdefault('end', time.time())
            self.report()
            for stream in self.logs.values():
                stream.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='root@201.18.216.184')
    parser.add_argument('--vus', type=int, nargs='+', default=[5, 10, 20, 40, 80, 160])
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--gateway-port', type=int, default=15000)
    parser.add_argument('--mail-port', type=int, default=18025)
    args = parser.parse_args()
    if args.seconds <= 0 or args.vus != sorted(set(args.vus)) or min(args.vus) <= 0:
        parser.error('VUs must be positive and strictly increasing; seconds must be positive')
    runner = Runner(args)
    runner.run()
    if runner.abort_reason or getattr(runner, 'restoration', {}).get('error'):
        raise SystemExit(1)
