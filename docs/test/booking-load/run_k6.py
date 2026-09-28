"""k6 workload supervisor; Python handles only setup, monitoring and recovery."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import queue
import shutil
import subprocess
import threading
import time
import urllib.request

from run import HERE, Runner
from analyze import analyze
from clock_sync import calibrate


def process_sample(pid):
    if os.name == 'nt':
        from ctypes import wintypes as w
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.restype = w.HANDLE
        handle = kernel.OpenProcess(0x1000 | 0x10, False, pid)
        if not handle:
            return {'pid': pid, 'unavailable': True}
        try:
            times = [w.FILETIME() for _ in range(4)]
            kernel.GetProcessTimes.argtypes = [w.HANDLE] + [ctypes.POINTER(w.FILETIME)]*4
            if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                raise ctypes.WinError(ctypes.get_last_error())
            cpu = sum((t.dwHighDateTime << 32) + t.dwLowDateTime for t in times[2:])/1e7
            class Memory(ctypes.Structure):
                _fields_ = [('cb', w.DWORD), ('faults', w.DWORD)] + [
                    (name, ctypes.c_size_t) for name in ['peak_rss', 'rss', 'peak_paged',
                    'paged', 'peak_nonpaged', 'nonpaged', 'pagefile', 'peak_pagefile']]
            memory = Memory()
            memory.cb = ctypes.sizeof(memory)
            psapi = ctypes.WinDLL('psapi')
            psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, ctypes.POINTER(Memory), w.DWORD]
            psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb)
            return {'pid': pid, 'cpu_seconds': cpu, 'rss_bytes': memory.rss}
        finally:
            kernel.CloseHandle.argtypes = [w.HANDLE]
            kernel.CloseHandle(handle)
    stat = Path('/proc/%d/stat' % pid).read_text().split(') ', 1)[1].split()
    return {'pid': pid, 'cpu_seconds': (int(stat[11])+int(stat[12]))/os.sysconf('SC_CLK_TCK'),
            'rss_bytes': int(stat[21])*os.sysconf('SC_PAGE_SIZE')}


class K6Runner(Runner):
    def __init__(self, args):
        super().__init__(args)
        self.pending = {}
        self.attempts = {}
        self.k6 = None
        self.reader = None
        self.owners = {}
        self.mail_lock = threading.Lock()
        self.mail_cursor = 0
        self.last_process_samples = {}
        self.logs['events'] = (self.out/'events.jsonl').open('a', encoding='utf-8')
        (self.out/'run.json').write_text(json.dumps({'run_id': self.run_id,
            'host': getattr(args, 'host', None), 'remote_dir': self.remote_dir,
            'generator': 'k6', 'vus': getattr(args, 'vus', []),
            'stage_seconds': getattr(args, 'seconds', None)}, indent=2))

    def collect_mail(self):
        with self.mail_lock:
            # The first scan reads retained historical mail before load starts.
            # Later polls use the normal bounded monitoring timeout.
            timeout = 180 if self.mail_cursor == 0 else 25
            output = self.ssh('python3 '+self.remote_dir+'/remote.py mail --after '+str(self.mail_cursor), timeout=timeout)
            records = json.loads(output.splitlines()[-1])
            for record in records:
                mid = record['id']
                if mid not in self.mails and mid not in self.baseline_mail:
                    record['observed_at'] = time.time()
                    self.mails[mid] = record
                    self.emit('mail', record)
            self.mail_cursor = max([self.mail_cursor]+[r['sequence'] for r in records])
            return {r['id']: r for r in records}

    def setup(self):
        super().setup()
        calibrate(self.args.host, self.out/'clock.json')

    def control(self, attributes=None):
        req = urllib.request.Request('http://127.0.0.1:%d/v1/status' % self.args.control_port)
        if attributes is not None:
            req.method = 'PATCH'
            req.data = json.dumps({'data': {'type': 'status', 'id': 'default',
                                          'attributes': attributes}}).encode()
            req.add_header('Content-Type', 'application/json')
        with urllib.request.urlopen(req, timeout=70 if attributes and attributes.get('paused') else 5) as response:
            return json.load(response)

    def ingest(self, event):
        self.emit('events', event)
        kind = event.pop('type')
        if kind in ('attempt', 'booking'):
            self.attempts[event['attempt']] = event
            self.pending[event['attempt']] = event
        elif kind == 'request':
            self.requests.append(event)
            self.emit('requests', event)
            if event['error']:
                self.emit('errors', event)
            if event['status'] in (0, 502, 503, 504):
                self.abort('Service outage response: '+str(event['status']))
        elif kind == 'flow':
            self.pending.pop(event['attempt'], None)
            self.attempts[event['attempt']] = event
            self.flows.append(event)
            self.emit('flows', event)
            if event.get('error'):
                self.emit('errors', event)
        elif kind == 'fatal':
            self.abort(event['reason'])

    def read_events(self):
        try:
            with (self.out/'k6.log').open('w', encoding='utf-8') as log:
                for line in self.k6.stdout:
                    log.write(line)
                    log.flush()
                    try:
                        event = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(event, dict) and 'type' in event:
                        self.ingest(event)
        except Exception as exc:
            self.abort('k6 event reader failed: '+str(exc))

    def generator_sample(self):
        samples = {}
        for name, pid in [('supervisor', os.getpid()), ('k6', self.k6.pid if self.k6 else None),
                          ('ssh_tunnel', self.tunnel.pid if self.tunnel else None)]:
            if pid:
                try:
                    samples[name] = process_sample(pid)
                except (OSError, ValueError):
                    samples[name] = {'pid': pid, 'unavailable': True}
                if samples[name].get('unavailable') and name in self.last_process_samples:
                    samples[name] = dict(self.last_process_samples[name], exited=True)
                else:
                    self.last_process_samples[name] = samples[name]
        return {'time': time.time(), 'processes': samples, 'active_flows': len(self.pending),
                'cpu_seconds': sum(s.get('cpu_seconds', 0) for s in samples.values()),
                'rss_bytes': sum(s.get('rss_bytes', 0) for s in samples.values())}

    def collector(self):
        mail_thread = threading.Thread(target=self.mail_collector, daemon=True)
        try:
            monitor = subprocess.Popen(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                '-o', 'ServerAliveInterval=5', '-o', 'ServerAliveCountMax=3', self.args.host,
                'python3 '+self.remote_dir+'/remote.py watch'], stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, text=True)
        except Exception as exc:
            self.abort('Resource monitor could not start: '+str(exc))
            return
        mail_thread.start()
        replies = queue.Queue()
        def read():
            for line in monitor.stdout:
                replies.put(line)
        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        last_sample = time.monotonic()
        try:
            while not self.collector_stop.is_set():
                try:
                    line = replies.get(timeout=.2)
                except queue.Empty:
                    if monitor.poll() is not None or time.monotonic()-last_sample > 25:
                        raise RuntimeError('Resource monitoring stream exited or stalled')
                    continue
                snap = json.loads(line)
                last_sample = time.monotonic()
                snap['generator'] = self.generator_sample()
                self.emit('resources', snap)
                if not snap['gatewayHealthy']:
                    self.abort('Gateway unhealthy')
                for name, state in snap['states'].items():
                    if not state['running'] or state['oom'] or not state['unchanged']:
                        self.abort('Container changed or failed: '+name)
        except Exception as exc:
            self.emit('errors', {'time': time.time(), 'collector': str(exc)})
            self.abort('Resource monitoring failed: '+str(exc))
        finally:
            if monitor.poll() is None:
                monitor.terminate()
            monitor.wait(timeout=15)
            reader.join(timeout=10)
            monitor.stdout.close()
            mail_thread.join(timeout=30)

    def mail_collector(self):
        failures = 0
        while not self.collector_stop.is_set():
            started = time.monotonic()
            try:
                self.collect_mail()
                failures = 0
            except Exception as exc:
                failures += 1
                self.emit('errors', {'time': time.time(), 'mail_collector': str(exc)})
                if failures >= 3:
                    self.abort('Mail monitoring failed three consecutive times')
            self.collector_stop.wait(max(0, 5-(time.monotonic()-started)))

    def start_k6(self):
        seats = []
        while not self.inventory.empty():
            seats.append(self.inventory.get_nowait())
        for name, value in [('seats', seats), ('accounts', self.accounts)]:
            path = self.private/(name+'.json')
            path.write_text(json.dumps(value), encoding='utf-8')
            path.chmod(0o600)
        env = dict(os.environ, SEATS_FILE=str(self.private/'seats.json'),
                   ACCOUNTS_FILE=str(self.private/'accounts.json'),
                   BASE_URL='http://127.0.0.1:%d' % self.args.gateway_port,
                   MAX_VUS=str(max(self.args.vus)),
                   MAX_DURATION=str(len(self.args.vus)*self.args.seconds+300)+'s')
        self.k6 = subprocess.Popen([self.args.k6, 'run', '--quiet',
            '--address', '127.0.0.1:%d' % self.args.control_port, '--log-format', 'raw',
            '--log-output', 'stdout', '--out', 'json='+str(self.out/'k6-metrics.jsonl'),
            str(HERE/'booking.k6.js')], env=env, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        self.reader = threading.Thread(target=self.read_events, daemon=True)
        self.reader.start()
        for _ in range(100):
            if self.k6.poll() is not None:
                self.reader.join(timeout=5)
                raise RuntimeError('k6 initialization failed: '+(self.out/'k6.log').read_text()[-2000:])
            try:
                self.control()
                return
            except OSError:
                time.sleep(.1)
        raise RuntimeError('k6 control API not ready')

    def drain_k6(self):
        if not self.k6:
            return
        if self.k6.poll() is None:
            # External executor pause finishes active iterations before preventing new work.
            if not self.control()['data']['attributes']['paused']:
                self.control({'paused': True})
            deadline = time.monotonic()+65
            # Allow the pipe reader to catch the last attempt-start events.
            time.sleep(.5)
            while self.pending and self.k6.poll() is None and time.monotonic() < deadline:
                time.sleep(.1)
            if self.pending:
                self.abort('In-flight k6 drain timed out')
            self.control({'stopped': True})
        self.k6.wait(timeout=15)
        self.reader.join(timeout=10)
        for flow in list(self.pending.values()):
            flow.update(ended=time.time(), interrupted=True,
                        error='k6 stopped before flow completion')
            self.flows.append(flow)
            self.emit('flows', flow)
        self.pending.clear()

    def settle(self):
        path = self.out/'attempts.json'
        keys = set(self.attempts) | {f['attempt'] for f in self.flows}
        path.write_text(json.dumps(sorted(keys)))
        subprocess.run(['scp', str(path), self.args.host+':'+self.remote_dir+'/attempts.json'],
                       check=True, capture_output=True)
        result = self.remote('settle', timeout=300)
        (self.out/'recovery.json').write_text(json.dumps(result, indent=2))
        recovered = {b['attempt']: b for b in result.get('bookings', [])}
        settled_ids = {r['booking_id'] for r in result.get('recovered', []) if r.get('status') == 'SUCCESS'}
        self.recovery_refs = {b['reference'] for b in result.get('bookings', []) if b['booking_id'] in settled_ids}
        for flow in self.flows:
            if flow['attempt'] in recovered:
                flow.update(recovered[flow['attempt']])

    def report(self):
        clock = json.loads((self.out/'clock.json').read_text()) if (self.out/'clock.json').exists() else None
        if clock:
            for mail in self.mails.values():
                mail.setdefault('source_time', mail['time'])
                mail['time'] = mail['source_time']-clock['server_minus_generator_seconds']
        def stage_at(timestamp):
            return next((s['vus'] for s in self.stages if s['start'] <= timestamp < s['end']), 'drain')
        for flow in self.flows:
            if flow['measured']:
                flow['origin_stage'] = stage_at(flow['started'])
        for request in self.requests:
            request['origin_stage'] = stage_at(request.get('started', request['time']))
        # Keep the append-only source journals intact; enrich a derived inventory.
        (self.out/'bookings.json').write_text(json.dumps(self.flows, indent=2))
        super().report()
        summary = json.loads((self.out/'summary.json').read_text())
        refs = {f['reference']: f for f in self.flows if f['measured'] and f.get('reference')}
        mail = [dict(m, origin_stage=refs[m['reference']]['origin_stage'],
                     arrival_stage=stage_at(m['time'])) for m in self.mails.values()
                if m.get('reference') in refs]
        end = self.stages[-1]['end'] if self.stages else time.time()
        summary['late_payment_completions'] = sum(f['paid'] and f['measured'] and f['paid_at'] >= end
                                                  for f in self.flows)
        summary['late_http_completions'] = sum(r['time'] >= end for r in self.requests)
        summary['duplicate_messages'] = sum(n-1 for n in summary['duplicates'].values())
        summary['generator'] = 'k6'
        summary['clock_alignment'] = clock
        mailed_refs = {m['reference'] for m in mail}
        summary['missing_recovered_payment_references'] = sorted(getattr(self, 'recovery_refs', set())-mailed_refs)
        (self.out/'email-reconciliation.json').write_text(json.dumps({
            'messages': mail, 'missing_paid_references': summary['missing_paid_references'],
            'duplicates': summary['duplicates']}, indent=2))
        (self.out/'summary.json').write_text(json.dumps(summary, indent=2))
        report = self.out/'REPORT.md'
        text = report.read_text().replace('Local Python generator', 'Local k6 generator')
        text += '\nLate HTTP completions: %d; late successful payments: %d; duplicate messages: %d.\n' % (
            summary['late_http_completions'], summary['late_payment_completions'], summary['duplicate_messages'])
        text += '\nEmail origin/arrival stages are in email-reconciliation.json. Stage p50/p95/p99 and endpoint failures are in summary.json.\n'
        if clock:
            text += '\nServer clock offset: %.3f seconds ahead of generator (round-trip uncertainty ±%.3f seconds). Email timestamps are aligned for stage attribution; source timestamps are retained.\n' % (
                clock['server_minus_generator_seconds'], clock['uncertainty_seconds'])
        report.write_text(text, encoding='utf-8')
        analyze(self.out)

    def run(self):
        collector = None
        try:
            self.setup()
            smoke = self.flow(0, measured=False)
            if not smoke or not smoke['paid']:
                raise RuntimeError('Smoke flow failed')
            deadline = time.monotonic()+120
            while time.monotonic() < deadline:
                self.collect_mail()
                if any(m['reference'] == smoke['reference'] for m in self.mails.values()):
                    break
                time.sleep(5)
            else:
                raise RuntimeError('Smoke confirmation email missing')
            print('Smoke booking, successful payment and email passed', flush=True)
            self.start_k6()
            collector = threading.Thread(target=self.collector, daemon=True)
            collector.start()
            for vus in self.args.vus:
                if self.stop.is_set():
                    break
                stage = {'vus': vus, 'start': time.time()}
                self.stages.append(stage)
                self.emit('events', {'type': 'stage_start', **stage})
                self.control({'vus': vus})
                print('STAGE %d VUs' % vus, flush=True)
                deadline = time.monotonic()+self.args.seconds
                while not self.stop.wait(.1) and time.monotonic() < deadline:
                    if self.k6.poll() is not None:
                        self.abort('k6 generator exited unexpectedly')
                    if self.tunnel.poll() is not None:
                        self.abort('SSH tunnel exited')
                stage['end'] = time.time()
                self.emit('events', {'type': 'stage_end', **stage})
        except BaseException as exc:
            self.abort(type(exc).__name__+': '+str(exc))
        finally:
            for stage in self.stages:
                stage.setdefault('end', time.time())
            try:
                self.drain_k6()
            except Exception as exc:
                self.abort('k6 drain failed: '+str(exc))
                if self.k6 and self.k6.poll() is None:
                    self.k6.terminate()
                    self.k6.wait(timeout=15)
                    if self.reader:
                        self.reader.join(timeout=10)
            # Save attempt keys before any remote operation, including interruption recovery.
            (self.out/'attempts.json').write_text(json.dumps(sorted(set(self.attempts) |
                                                       {f['attempt'] for f in self.flows})))
            if self.deployed:
                try:
                    self.settle()
                    deadline = time.monotonic()+120
                    while True:
                        self.collect_mail()
                        expected = {f['reference'] for f in self.flows if f['paid'] and f.get('reference')}
                        expected |= getattr(self, 'recovery_refs', set())
                        if expected <= {m['reference'] for m in self.mails.values()} or time.monotonic() >= deadline:
                            break
                        time.sleep(5)
                    # Final overlap window includes immediate duplicate deliveries.
                    time.sleep(5)
                    self.collect_mail()
                except Exception as exc:
                    self.emit('errors', {'time': time.time(), 'recovery_error': str(exc)})
                    self.abort('Payment/email recovery failed: '+str(exc))
            self.collector_stop.set()
            if collector:
                collector.join(timeout=40)
            if self.deployed:
                try:
                    self.restoration = self.remote('restore', timeout=300)
                except Exception as exc:
                    self.restoration = {'error': str(exc), 'manual_recovery_directory': self.remote_dir}
                (self.out/'restoration.json').write_text(json.dumps(self.restoration, indent=2))
            if self.tunnel:
                self.tunnel.terminate()
                self.tunnel.wait(timeout=15)
                self.tunnel_log.close()
            if self.k6:
                self.k6.stdout.close()
            for name in ['accounts.json', 'seats.json']:
                (self.private/name).unlink(missing_ok=True)
            self.report()
            for stream in self.logs.values():
                stream.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='root@201.18.216.184')
    parser.add_argument('--vus', type=int, nargs='+', default=[5, 10, 20, 40, 80, 160])
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--accounts', dest='account_count', type=int,
                        help='Number of traveler accounts shared across VUs; defaults to all seeded accounts')
    parser.add_argument('--gateway-port', type=int, default=15000)
    parser.add_argument('--mail-port', type=int, default=18025)
    parser.add_argument('--control-port', type=int, default=16565)
    bundled = list((HERE/'private'/'tools').glob('k6-v1*/k6.exe'))
    parser.add_argument('--k6', default=shutil.which('k6') or (str(bundled[0]) if bundled else 'k6'))
    args = parser.parse_args()
    if args.account_count is not None and args.account_count <= 0:
        parser.error('Account count must be positive')
    if args.seconds <= 0 or not args.vus or args.vus != sorted(set(args.vus)) or min(args.vus) <= 0:
        parser.error('VUs must be positive and increasing; seconds must be positive')
    version = subprocess.check_output([args.k6, 'version'], text=True).strip()
    print(version, flush=True)
    if not re.search(r'\bv1\.', version):
        parser.error('This harness requires k6 1.x (validated with 1.8.2); k6 2.x removed externally-controlled')
    runner = K6Runner(args)
    runner.run()
    raise SystemExit(1 if runner.abort_reason or getattr(runner, 'restoration', {}).get('error') else 0)
