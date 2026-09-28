"""Rebuild k6 reports from retained journals without making network requests."""
import argparse
import json
from pathlib import Path
import subprocess
import time

from analyze import records
from run_k6 import K6Runner


def rebuild(root):
    meta = json.loads((root/'run.json').read_text())
    previous = json.loads((root/'summary.json').read_text()) if (root/'summary.json').exists() else {}
    runner = K6Runner.__new__(K6Runner)
    runner.out, runner.run_id = root, meta['run_id']
    runner.requests = records(root/'requests.jsonl')
    runner.flows = json.loads((root/'bookings.json').read_text()) if (root/'bookings.json').exists() else records(root/'flows.jsonl')
    runner.mails = {m['id']: m for m in records(root/'mail.jsonl')}
    runner.stages = previous.get('stages', [])
    if not runner.stages:
        events = records(root/'events.jsonl')
        runner.stages = [dict(vus=e['vus'], start=e['start'], end=e['end'])
                         for e in events if e['type'] == 'stage_end']
    fatal = next((e['fatal'] for e in records(root/'errors.jsonl') if e.get('fatal')), None)
    runner.abort_reason = previous.get('abort_reason', fatal or 'Rebuilt from journals; original completion status unavailable')
    runner.restoration = json.loads((root/'restoration.json').read_text()) if (root/'restoration.json').exists() else previous.get('restoration')
    recovery = json.loads((root/'recovery.json').read_text()) if (root/'recovery.json').exists() else {}
    paid_ids = {r['booking_id'] for r in recovery.get('recovered', []) if r.get('status') == 'SUCCESS'}
    recovered = {b['attempt']: b for b in recovery.get('bookings', [])}
    for flow in runner.flows:
        if flow['attempt'] in recovered:
            flow.update(recovered[flow['attempt']])
    runner.recovery_refs = {b['reference'] for b in recovery.get('bookings', []) if b['booking_id'] in paid_ids}
    runner.logs = {name: (root/(name+'.jsonl')).open('a') for name in ['requests', 'flows']}
    try:
        runner.report()
    finally:
        for stream in runner.logs.values():
            stream.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--refresh-mail', action='store_true', help='Fetch final metadata and verify restoration over SSH')
    args = parser.parse_args()
    if args.refresh_mail:
        meta = json.loads((args.directory/'run.json').read_text())
        def call(mode):
            result = subprocess.check_output(['ssh', '-o', 'BatchMode=yes', meta['host'],
                'python3 '+meta['remote_dir']+'/remote.py '+mode], text=True, timeout=120)
            return json.loads(result.splitlines()[-1])
        mail = call('mail --after 0')
        (args.directory/'final-mail.json').write_text(json.dumps(mail, indent=2))
        seen = {m['id'] for m in records(args.directory/'mail.jsonl')}
        with (args.directory/'mail.jsonl').open('a') as stream:
            for record in mail:
                if record['id'] not in seen:
                    stream.write(json.dumps(dict(record, observed_at=time.time()))+'\n')
        restoration = call('verify')
        (args.directory/'restoration.json').write_text(json.dumps(restoration, indent=2))
    rebuild(args.directory)
