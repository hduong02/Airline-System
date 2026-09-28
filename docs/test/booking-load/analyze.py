"""Add resource and per-endpoint evidence to a finished run report."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from run import percentile


def records(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line]


def analyze(root):
    summary = json.loads((root/'summary.json').read_text())
    resources = records(root/'resources.jsonl')
    requests = records(root/'requests.jsonl')
    flows = json.loads((root/'bookings.json').read_text()) if (root/'bookings.json').exists() else records(root/'flows.jsonl')
    errors = records(root/'errors.jsonl')
    clock = summary.get('clock_alignment') or {}
    offset = clock.get('server_minus_generator_seconds', 0)
    containers = defaultdict(list)
    for sample in resources:
        for container in sample['stats']:
            containers[container['Name']].append(container)
    peaks = {name: {'peak_cpu_percent': max(float(c['CPUPerc'].rstrip('%')) for c in values),
                    'peak_memory_percent': max(float(c['MemPerc'].rstrip('%')) for c in values)}
             for name, values in containers.items()}
    generator_cpu = []
    intervals = [b['generator']['time']-a['generator']['time'] for a, b in zip(resources, resources[1:])]
    for a, b in zip(resources, resources[1:]):
        elapsed = b['generator']['time']-a['generator']['time']
        cpu_delta = b['generator']['cpu_seconds']-a['generator']['cpu_seconds']
        if elapsed > 0 and cpu_delta >= 0:
            generator_cpu.append(100*cpu_delta/elapsed)
    endpoint_results = {}
    for endpoint in sorted({r['endpoint'] for r in requests}):
        selected = [r for r in requests if r['endpoint'] == endpoint]
        endpoint_results[endpoint] = {'requests': len(selected),
            'status_counts': dict(Counter(str(r['status']) for r in selected)),
            'p50_ms': percentile([r['latency_ms'] for r in selected], .5),
            'p95_ms': percentile([r['latency_ms'] for r in selected], .95),
            'p99_ms': percentile([r['latency_ms'] for r in selected], .99)}
    for stage in summary['stages']:
        selected = [s for s in resources if stage['start'] <= s['time']-offset < stage['end']]
        stage['sampled_active_flows_min'] = min((s['generator']['active_flows'] for s in selected), default=0)
        stage['sampled_active_flows_max'] = max((s['generator']['active_flows'] for s in selected), default=0)
        stage['failed_originating_flows'] = sum(f.get('measured', False) and not f['paid'] and not f.get('interrupted') and f['origin_stage'] == stage['vus'] for f in flows)
        stage['interrupted_originating_flows'] = sum(f.get('interrupted', False) and f['origin_stage'] == stage['vus'] for f in flows)
    summary['endpoints'] = endpoint_results
    summary['resources'] = {'samples': len(resources), 'containers': peaks,
        'generator_peak_rss_bytes': max((s['generator'].get('rss_bytes', 0) for s in resources), default=0),
        'generator_peak_cpu_percent_one_core': max(generator_cpu, default=0),
        'generator_average_cpu_percent_one_core': sum(generator_cpu)/max(1, len(generator_cpu)),
        'sample_interval_p50_seconds': percentile(intervals, .5),
        'sample_interval_p95_seconds': percentile(intervals, .95),
        'sample_interval_max_seconds': round(max(intervals), 2) if intervals else None,
        'monitoring_errors': sum('collector' in e or 'mail_collector' in e for e in errors),
        'container_failure_observed': any(not state['running'] or state['oom'] or not state['unchanged']
                                          for sample in resources for state in sample['states'].values())}
    recovery = json.loads((root/'recovery.json').read_text()) if (root/'recovery.json').exists() else None
    summary['recovery'] = {key: value for key, value in recovery.items() if key != 'bookings'} if recovery else None
    (root/'summary.json').write_text(json.dumps(summary, indent=2))
    report = root/'REPORT.md'
    text = report.read_text(encoding='utf-8').split('\n## Endpoint results')[0]
    text += '\n## Endpoint results\n\n| Endpoint | Requests | Status counts | p50 ms | p95 ms | p99 ms |\n|---|---:|---|---:|---:|---:|\n'
    for endpoint, values in endpoint_results.items():
        text += '| %s | %d | %s | %s | %s | %s |\n' % (endpoint, values['requests'], values['status_counts'], values['p50_ms'], values['p95_ms'], values['p99_ms'])
    text += '\n## Resource evidence\n\n'
    text += 'Generator CPU: average %.1f%%, peak %.1f%% of one local core. Monitoring errors: %d. Container failure observed: %s.\n\n' % (
        summary['resources']['generator_average_cpu_percent_one_core'], summary['resources']['generator_peak_cpu_percent_one_core'],
        summary['resources']['monitoring_errors'], summary['resources']['container_failure_observed'])
    text += 'Observed resource sampling interval: p50 %s s, p95 %s s, maximum %s s (requested cadence: 5 s).\n\n' % (
        summary['resources']['sample_interval_p50_seconds'], summary['resources']['sample_interval_p95_seconds'],
        summary['resources']['sample_interval_max_seconds'])
    validation = root/'monitor-validation.json'
    if validation.exists():
        check = json.loads(validation.read_text())
        text += 'After this measured run, the harness was changed to stream resource samples over a persistent SSH connection. A separate %d-sample check observed intervals %s seconds with no booking traffic. This change was not rerun under the full load profile; the measurements above retain the actual run cadence. See monitor-validation.json.\n\n' % (
            check['samples'], check['intervals_seconds'])
    text += '| Container | Peak CPU % | Peak memory limit % |\n|---|---:|---:|\n'
    for name, values in sorted(peaks.items()):
        text += '| %s | %.1f | %.1f |\n' % (name, values['peak_cpu_percent'], values['peak_memory_percent'])
    text += '\nDocker CPU can exceed 100% when a container uses multiple cores. Peaks are sampled, not continuous maxima.\n'
    text += '\nRecovery (excluded from measured traffic): `%s`. Full scoped booking inventory is retained in recovery.json.\n' % json.dumps(summary['recovery'])
    report.write_text(text, encoding='utf-8')
    print(json.dumps({'stages': summary['stages'], 'resources': summary['resources']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    analyze(parser.parse_args().directory)
