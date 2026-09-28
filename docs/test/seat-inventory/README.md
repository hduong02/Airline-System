# Seat inventory integration tests

**Verified on the VPS on October 26, 2026: N = 100; zero oversold seats; all 9 tests passed against MySQL 8.0.46.**
See the [VPS report](REPORT-VPS-20261026.md), [machine-readable evidence](results/vps-20261026-n100.json),
and [raw JUnit output](results/vps-20261026-n100-output.txt).

The suite exercises the production `SeatInstanceServiceImpl`, real JPA repositories,
and Spring transaction annotations. Each test creates a fresh database. Workers
share a start barrier and use independent transactions and database connections.
No application services, Kafka, or HTTP server need to be running.

From the repository root, with Java 25 and Maven installed:

```powershell
mvn -f microservices/pom.xml -pl services/seat-service -am -Pseat-inventory-tests test "-Dtest=SeatInventoryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

The profile selects the test sources in this folder. Without the profile, the
service's usual test sources remain selected. Reports are written to
`microservices/services/seat-service/target/surefire-reports/`.

To change N from the default 32 competing attempts (allowed range: 2–128), append
`"-Dinventory.attempts=64"` to the command. Both overselling scenarios print the
attempt count, committed reservations, and oversold count after assertions pass.
Database errors, timeouts, deadlocks, and unexpected rejection messages fail the
suite rather than count as expected capacity rejections.

| Scenario | Required result |
| --- | --- |
| N bookings compete for one seat | Exactly one committed owner; N−1 unavailable rejections |
| N distinct seats compete for a cabin with capacity below N | Exactly capacity committed; remaining attempts rejected; zero oversold seats |
| N duplicate reservation retries for one booking | All retries succeed; capacity consumed once; repeated confirmation consumes no additional capacity |
| N distinct bookings cancel concurrently | Every seat released; counter returns to zero; one marker per cancelled booking |
| Repeated cancellation and late reservation/confirmation | Marker persists; no negative count or reacquisition; another booking's reservation survives an old cancellation |
| In-flight reservation resumes after cancellation commits | Reservation sees the committed marker and is rejected |
| Multi-cabin reservation fails in the second cabin | First cabin's earlier capacity increment rolls back; all seats remain available |
| Cancellation fails after marker insertion | Marker rolls back; original reservation remains intact and can be retried |
| Duplicate seat IDs in a reservation | Request rejected without inventory changes |

Every inventory assertion checks persisted occupancy against each cabin counter,
capacity bounds, owner IDs, availability flags, and booked flags.

## Database scope

The default database is in-memory H2. This verifies transaction and inventory
invariants locally; it does not establish production MySQL behavior or HTTP
throughput. The in-flight test pauses the repository boundary before acquiring
the seat lock to deterministically exercise visibility of a committed marker.

To repeat against MySQL, create a dedicated disposable schema and set these
environment variables before running the same command:

```powershell
$schema = 'seat_inventory_test_' + [guid]::NewGuid().ToString('N')
# Create this empty schema on your test MySQL instance first.
$env:SEAT_INVENTORY_TEST_JDBC_URL = "jdbc:mysql://localhost:3306/$schema"
$env:SEAT_INVENTORY_TEST_JDBC_USER = 'test_user'
$env:SEAT_INVENTORY_TEST_JDBC_PASSWORD = '<test password>'
```

Only URLs naming `seat_inventory_test_<32 lowercase UUID hex digits>` are
accepted. Hibernate creates and drops the model tables inside that schema for
each test. Use a test account restricted to that disposable schema; remove the
schema and environment variables afterward. The suite does not create or delete
the schema itself.

A passing run supports “zero oversold seats across N competing reservation
attempts” for the tested database and scenarios. The retained VPS run fills N with **100**.
Concurrent duplicate cancellation
delivery for the same booking is outside this suite; duplicate cancellations are
tested sequentially, while cancellation of distinct bookings is concurrent.

## Repeating on the VPS

After running the local Maven command above to compile the suite and generate its
dependency report, run `python docs/test/seat-inventory/package_vps.py`. This creates
`microservices/services/seat-service/target/seat-inventory-tests.zip` containing
only compiled tests, the JUnit runner/libraries, and `remote_vps.py`.

Upload the archive into a new private directory on the VPS. Extract it there with
`python3 -m zipfile -e seat-inventory-tests.zip .`, then run
`python3 remote_vps.py --attempts 100` from that directory. The remote runner uses
the immutable image ID of the running `seat-service`, including its production
classes and dependencies. It requires Docker, Python 3.8+, the `seat-service` and
`seatdb` containers, and sufficient MySQL connection headroom. Its temporary
container is limited to 2 CPUs and 768 MiB RAM.

The runner creates a UUID schema and restricted test account, runs the tests, and
removes the temporary container, schema, account, and credential file in cleanup.
It records image identity, test results, cleanup checks, and before/after service
container identities in `result.json`; raw output is in `test-output.txt`. Copy
both files back into this folder's `results/` directory for each retained run.
