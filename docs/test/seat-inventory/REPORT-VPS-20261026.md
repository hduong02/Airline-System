# VPS seat inventory verification

**N = 100 competing attempts per concurrency scenario. Zero oversold seats. All 9 integration tests passed.**

Suggested wording:

> Engineered concurrency-safe seat inventory, verified with **0 oversold seats across 100 competing reservation attempts** on VPS MySQL, through pessimistic seat locking, atomic capacity updates, and cancellation markers; added integration coverage for concurrent reservations, cancellations, duplicate retries, and transactional rollback.

| Scenario | Attempts | Committed reservations | Expected rejections | Oversold seats |
| --- | ---: | ---: | ---: | ---: |
| One seat, competing booking IDs | 100 | 1 | 99 unavailable | 0 |
| Distinct seats, cabin capacity 5 | 100 | 5 | 95 capacity reached | 0 |

The nine tests also passed concurrent retries for the same reservation,
concurrent cancellation of distinct bookings, sequential cancellation retries,
late messages after cancellation, visibility of a committed cancellation during
an in-flight reservation, multi-cabin capacity rollback, cancellation-marker
rollback, and duplicate seat-ID validation. The retry and distinct-booking
cancellation tests each used 100 worker attempts as well.

## Environment and method

- VPS: `srv2036842` (`201.18.216.184`).
- Database: MySQL **8.0.46**, existing `seatdb` container, dedicated disposable schema.
- Production image: `andyy02/seat-service:booking-reservations-20261023`.
- Immutable image ID: `sha256:dd9dcd76f1b6781df2161af6088727206b7b5b2100ae431c47a02fc6ff222e03`.
- Java runtime: deployed image's Temurin **25.0.4.1**.
- Runner: isolated temporary container; 2 CPUs, 768 MiB memory, 512 MiB Java heap.
- Start barrier: 100 workers released together; each service call uses its own transaction and connection.
- MySQL before testing: 11 connected sessions; configured maximum 151.

The uploaded bundle contains test classes and JUnit, with **no replacement production
classes**. The test classpath loads the production service, repositories, entities,
and dependencies from the immutable deployed image. Spring applies the production
transaction annotations, including `READ_COMMITTED` on reservations.

This is service/database integration verification, not an HTTP load test or a
measurement of maximum supported concurrency. N = 100 is a tested count, not a
claimed system limit. Duplicate cancellations for a single booking are tested
sequentially; concurrent cancellations use distinct bookings.

## Evidence and cleanup

- [Machine-readable result and service snapshots](results/vps-20261026-n100.json).
- [Raw JUnit output](results/vps-20261026-n100-output.txt).
- JUnit: `tests=9 passed=9 failed=0 skipped=0`; runner exit code **0**.
- Disposable schema removed: **verified**.
- Restricted test account removed: **verified**.
- Temporary container removed and credential file deleted by the runner.
- Existing service container IDs, start timestamps, and restart counts: **unchanged**.

The raw output includes Hibernate warnings when initial `create-drop` cleanup
attempts to remove tables/constraints that do not yet exist in the empty schema.
The final JUnit summary records the test outcomes.

VPS artifacts are retained in `/root/seat-inventory-20261026-cffa8bf1/` without the
test credential file. Reproduction instructions are in the [README](README.md).
