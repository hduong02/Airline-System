# Booking ramp-up test

The load-test report is `results/REPORT.md`. It includes stage results, endpoint latency, resource peaks, restoration status, and booking-email reconciliation.

Run from the repository root with Python 3.8+, working SSH/SCP access, and k6 1.x:

```powershell
python -B docs/test/booking-load/run_k6.py --vus 10 20 40 80 160
```

Each stage lasts 120 seconds. The default server is `root@201.18.216.184`; use `--host`, `--seconds`, or `--accounts` to override settings. The retained `private/tools/k6-v1*/k6.exe` binary is detected automatically. k6 2.x is incompatible with this harness.

`run_k6.py` supervises `booking.k6.js`. Shared setup and recovery are in `run.py`; server operations and the payment simulator are in `remote.py` and `simulator.cjs`. `clock_sync.py` aligns timestamps, and `analyze.py` generates report details. `report_k6.py` can rebuild reports for future runs while their raw journals remain available.

The harness authenticates seeded travelers, selects unique eligible seats, checks one smoke booking/payment/email, and runs booking creation followed by simulated payment verification. It collects resource and MailHog metadata, reconciles its own payments, and restores payment-service. Local ports 15000, 18025, and 16565 must be available.

Each run creates fresh `results/<UTC-run-id>/` and `private/<UTC-run-id>/` directories. Test bookings and mail remain on the server.

Rebuild a report from a new run's raw journals:

```powershell
python -B docs/test/booking-load/report_k6.py docs/test/booking-load/results/<UTC-run-id>
```

