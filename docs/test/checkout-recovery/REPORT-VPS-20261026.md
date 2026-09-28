# Checkout recovery — VPS verification

**X = 100%; N = 100 simulated failures.** All 100 planned injected failures
recovered, and all **174 test executions passed** (118 booking, 56 payment;
zero failed, aborted, or skipped).

Suggested résumé bullet:

> Built automatic recovery for interrupted bookings and payments, achieving **100% recovery across 100 simulated failures on a VPS**, by saving checkout progress and retrying unfinished steps; prevented duplicate payment creation and checked payment status before releasing seats from expired bookings.

## Measured workload

| Injected failure | Recovered / attempted |
| --- | ---: |
| Seat reservation response lost | 20 / 20 |
| Reservation worker abandoned its lease | 20 / 20 |
| Payment initiation response lost | 20 / 20 |
| Payment provider unavailable | 20 / 20 |
| Checkout response database write fails | 20 / 20 |
| **Total** | **100 / 100 (100%)** |

Each invocation starts from fresh persisted fixtures, injects one interruption,
and asserts the recovered checkout and stable booking/payment identity. Booking
trials use the real recovery job. Payment trials retry the real payment service.
Replays must preserve identity without additional provider calls. Additional
tests verify concurrent requests, lease ownership, compensation, payment
reconciliation before expiry cancellation, and verification/expiry races.

The denominator is the 100 planned parameterized fault trials. The other 74 test
executions are correctness checks and do not contribute to the recovery rate.
This is a deterministic service-level simulation with mocked remote boundaries;
it does not measure a production recovery rate, real Stripe availability, HTTP
network faults, Kafka delivery, frontend restoration, or process-kill recovery.
Lease and backoff deadlines are advanced in test fixtures. Duplicate payment
checks establish one persisted payment and stable provider identity; Stripe's
actual deduplication is outside this mocked-provider run.

## Environment and isolation

- VPS: `srv2036842`, `201.18.216.184`.
- Databases: **MySQL 8.0.46** in the existing booking/payment DB containers.
- Production code and dependencies: loaded from the immutable deployed images,
  with test bytecode and test libraries supplied separately.
- Booking image: `sha256:b9f1ff1509999490cff7ecf457db02e867f9557cfb6a4da284efee7cc63965e7`.
- Payment image: `sha256:4a5daa6deb4d80cfdd46ddc143185e06a4d64ac03d3f9bb87870f635321b860b`.
- Test archive SHA-256: `c00e2fcea2ce5da1b1dee5a6c048b7c40c729e2eaab6bfb5a39b02ee3efa9ee4`.
- Each test container: one CPU, 768 MiB memory limit; services tested sequentially.

Both UUID test schemas and restricted accounts were removed and removal verified.
Credential files and temporary test containers were removed. Application
containers were not deployed or restarted; all running container IDs, start
times, and restart counts matched before and after the run.

## Evidence and reproduction

- [Machine-readable VPS result](results/vps-20261026-n100.json), including individual
  trials, image identities, cleanup checks, and container snapshots.
- [Booking JUnit output](results/booking-output.txt).
- [Payment JUnit output](results/payment-output.txt).
- [Test scope and reproduction instructions](README.md).

VPS artifacts are retained in `/root/checkout-recovery-20261026-1515/` without
credential files. The local H2 preflight also passed all 133 selected tests.
