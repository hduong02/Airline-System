# Checkout recovery verification

**VPS result: 100% recovery across 100 simulated failures;
all 174 test executions passed on MySQL 8.0.46.** See the
[report](REPORT-VPS-20261026.md) and [raw result](results/vps-20261026-n100.json).

The measured workload contains **100 injected failures**: five deterministic
failure scenarios, each repeated 20 times with fresh database fixtures. Only the
parameterized `simulatedInterruption*` tests contribute to N. Other tests check
idempotency, concurrency, cleanup, and expiry safety and do not inflate N.

| Scenario | Trials | Required outcome |
| --- | ---: | --- |
| Seat reservation response lost | 20 | Recovery job retries the same booking, saves checkout, and replays without further calls |
| Reservation worker abandoned its lease | 20 | Live lease blocks work; after expiry, recovery job saves checkout |
| Payment initiation response lost | 20 | Recovery job respects backoff, retries payment for the same booking, and avoids another seat reservation |
| Payment provider unavailable | 20 | Payment retry reuses the persisted payment ID and transaction identity |
| Checkout response database write fails | 20 | Payment retry keeps the same provider identity, saves checkout, and replays without another provider call |

Booking trials call the real recovery job and real transactional services.
Payment trials retry the real payment service. Both use real JPA repositories.
Remote clients, provider operations, and event transport are mocked; no real
payment is charged. Failures are injected exceptions or seeded abandoned leases,
not operating-system process kills. Retry deadlines are advanced in the test
database rather than waiting 30/120 seconds. Repetition verifies the specified
cases; this is not a random fault distribution or a production recovery SLA.

Additional assertions cover one booking/payment row per attempt, durable checkout
responses, replay, stable provider idempotency identity, concurrent initiation,
paid/processing/unknown payment status at expiry, and payment verification racing
with expiry. The expiry job tests verify payment reconciliation precedes seat
release and that unavailable or pending payment status keeps seats held.

## Local run

From the repository root in PowerShell:

```powershell
mvn -o -f microservices/pom.xml -pl services/booking-service,services/payment-service -am test "-Dtest=BookingReservationRecoveryTest,BookingExpiryJobTest,BookingExpiryServiceTest,PaymentInitiationIntegrationTest,PaymentExpiryReconciliationTest,PaymentExpiryConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"
python docs/test/checkout-recovery/package_vps.py
```

The local default database is H2. Remove `-o` if dependencies are not cached.
Packaging includes test classes and test libraries, plus the supporting booking
flow suite. Application classes are loaded from the VPS's immutable image IDs.

## VPS run

Copy `microservices/services/booking-service/target/checkout-recovery-tests.zip`
to a fresh private directory on the authorized VPS, then run there:

```sh
umask 077
python3 -m zipfile -e checkout-recovery-tests.zip .
python3 remote_vps.py
```

The runner verifies the package manifest, creates a UUID schema and restricted
account in each service's MySQL container, and runs isolated test containers
sequentially (one CPU, 768 MiB limit). It removes temporary containers, schemas,
accounts, and credential files in `finally` blocks and records cleanup checks.
It does not restart or deploy application services. Before/after snapshots check
running container identity, start time, and restart count.

`result.json` records the exact image IDs, database versions, package digest,
per-scenario counts, JUnit summaries, and cleanup. Raw output is retained in
`booking-output.txt` and `payment-output.txt`. A passing run requires all 100
unique planned trials, all selected tests passing, verified cleanup, and unchanged
service containers. A failed run retains denominator 100 rather than shrinking
N to the trials that finished.
