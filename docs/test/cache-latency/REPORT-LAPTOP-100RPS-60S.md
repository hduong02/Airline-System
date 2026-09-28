# Laptop-generated cold/warm benchmark: 100 RPS for one minute

**The laptop maintained approximately 100 RPS for both passes.** Average
request latency decreased from 79.92 ms cold to 52.16 ms warm (34.73% lower).
p95 decreased from 179.71 ms to 61.65 ms (65.70% lower).

## Method

The generator was a local Python process on the user's Windows laptop
(eight logical CPUs), sending HTTP requests directly to
`http://201.18.216.184:5000/api/flight-instances/{id}`. Requests crossed the
laptop-to-server network path. SSH was used solely for the previously approved
seeded traveler login and selected Redis cache operations. HTTP load was not
generated remotely or routed through an SSH tunnel.

The account password remained on the server. A short-lived login token was
captured into laptop process memory through SSH and was neither printed to the
user nor saved to any file. The report contains no credentials or response bodies.

After one correctness probe, the harness deleted only `flightInstances::1`
through `flightInstances::6000` and verified all selected entries were absent.
It scheduled IDs 1–6,000 once each over 60 seconds at 100 RPS, waited for all
responses, checked cache population, and immediately replayed the same IDs over
another minute. Dependencies' caches, JVMs, services, and database state were
left running. No application configuration or database writes were performed.

Both passes use persistent HTTP connections, reused by worker threads where
available. The harness allows up to 1,000 concurrent workers; scheduling delays
are measured separately from request latency. Percentiles use nearest rank.

## Results

| Metric | Cold | Warm | Latency reduced |
| --- | ---: | ---: | ---: |
| Requests | 6,000 | 6,000 | — |
| Successful / errors | 6,000 / 0 | 6,000 / 0 | — |
| Actual request-start rate | 99.971 RPS | 99.981 RPS | — |
| Mean latency | 79.92 ms | 52.16 ms | 27.76 ms (34.73%) |
| p50 latency | 61.00 ms | 49.40 ms | 11.60 ms (19.01%) |
| p95 latency | 179.71 ms | 61.65 ms | 118.06 ms (65.70%) |
| p99 latency | 281.62 ms | 97.42 ms | 184.20 ms (65.41%) |
| Maximum latency | 577.25 ms | 222.71 ms | 354.54 ms (61.42%) |
| Elapsed including response drain | 60.065 s | 60.055 s | — |
| Completions / elapsed time | 99.892 RPS | 99.908 RPS | — |
| p95 request-start scheduling delay | 14.75 ms | 14.78 ms | — |
| Maximum request-start scheduling delay | 92.41 ms | 162.71 ms | — |
| Selected keys present after pass | 6,000 | 6,000 | — |

Reductions use unrounded measurements: cold minus warm, and
(cold minus warm) / cold × 100. Request latency includes network and HTTP
connection work when needed; it excludes the separately reported client
scheduling delay. The rate is an average over the pass, not a claim of perfectly
uniform inter-request spacing. No user latency threshold was specified.

All 12,000 requests returned HTTP 200 and the requested instance ID. All 6,000
cold/warm pairs had identical canonical JSON hashes. The first/last selected
keys had at least 117 seconds of TTL before replay; the first key retained
54 seconds afterward. Configured TTL is 180 seconds. All selected keys existed
after both passes, with no global cache flush.

### Latency by scheduled arrival group

Each group contains 1,000 requests per pass.

| Arrival window | Cold mean | Cold p95 | Warm mean | Warm p95 | Mean reduced | p95 reduced |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0–10 s | 106.35 ms | 289.37 ms | 50.73 ms | 60.41 ms | 52.30% | 79.12% |
| 10–20 s | 82.94 ms | 174.56 ms | 54.41 ms | 80.00 ms | 34.40% | 54.17% |
| 20–30 s | 87.08 ms | 187.41 ms | 53.11 ms | 62.04 ms | 39.01% | 66.90% |
| 30–40 s | 76.77 ms | 166.73 ms | 52.18 ms | 60.22 ms | 32.03% | 63.88% |
| 40–50 s | 64.85 ms | 114.69 ms | 51.42 ms | 59.99 ms | 20.71% | 47.69% |
| 50–60 s | 61.55 ms | 96.19 ms | 51.13 ms | 59.73 ms | 16.93% | 37.91% |

The cold pass did not show a growing backlog. Cold latency declined over the
minute, while warm latency remained around a 52 ms mean over the network path.
That warm baseline includes network, gateway, application, and Redis work; it
is not an isolated network round-trip measurement.

## Comparison limits

This paired run is evidence for reduced latency over the laptop's network
path under this workload. It is not a general production capacity guarantee.
The target cache starts cold; downstream caches remain in their existing state.
Cold always precedes warm. Server CPU and per-service call counts were not
sampled, so backend bottlenecks and exact per-request cache hits are not traced.

## Artifacts and reproduction

- `results-laptop-100rps-60s-2026-10-15.json`: all 12,000 request records,
  summary metrics, cache checks, and ten-second arrival groups.
- `benchmark.py`: shared harness with laptop mode via `--ssh-host`, accepting
  up to 6,000 IDs. Server mode remains available without this argument.

Run locally from the repository in PowerShell, with the same approved account
and SSH access:

```powershell
python docs/test/cache-latency/benchmark.py `
  --base-url http://201.18.216.184:5000 `
  --ssh-host root@201.18.216.184 `
  --credentials /root/future-seed-20261015/credentials.json `
  --count 6000 --rps 100 |
  Out-File -Encoding utf8 docs/test/cache-latency/results-laptop-new.json
```

All created files remain under `docs/test/cache-latency`. SSH helpers run from
stdin and create no remote benchmark files. Cache entries expire normally.
