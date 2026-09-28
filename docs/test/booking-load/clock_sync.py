"""Measure server clock offset without changing either machine's clock."""
import argparse
import json
from pathlib import Path
import queue
import shlex
import subprocess
import threading
import time


def calibrate(host, output):
    samples = []
    code = "import sys,time\nprint('ready',flush=True)\nfor line in sys.stdin:\n print(time.time(),flush=True)"
    process = subprocess.Popen(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
        '-o', 'ServerAliveInterval=5', '-o', 'ServerAliveCountMax=3', host,
        'python3 -u -c '+shlex.quote(code)], text=True, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    replies = queue.Queue()
    def read():
        for line in process.stdout:
            replies.put(line)
    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    try:
        if replies.get(timeout=20).strip() != 'ready':
            raise RuntimeError('Clock calibration handshake failed')
        # Measure over the established connection, excluding SSH setup latency.
        for _ in range(5):
            start = time.time()
            process.stdin.write('ping\n')
            process.stdin.flush()
            value = replies.get(timeout=20)
            end = time.time()
            samples.append({'server_minus_generator_seconds': float(value)-(start+end)/2,
                            'round_trip_seconds': end-start, 'sampled_at': end})
        process.stdin.close()
        process.wait(timeout=10)
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        reader.join(timeout=5)
        process.stdout.close()
        if not process.stdin.closed:
            process.stdin.close()
    best = min(samples, key=lambda s: s['round_trip_seconds'])
    result = dict(best, uncertainty_seconds=best['round_trip_seconds']/2, samples=samples)
    Path(output).write_text(json.dumps(result, indent=2))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    print(json.dumps(calibrate(args.host, args.output)))
