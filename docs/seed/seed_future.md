# Future flight seed

`seed_future.py` runs **on the Docker database host**, using container names from
the deployed airline system. It requires Python 3.9+ and the `bcrypt` module.

The dataset contains 17 cities/airports, nine airlines, 272 directed routes,
21,216 flight instances departing daily from October 15 through December 31,
2026, and 2,545,920 available seats (108 economy + 12 business per instance).
There are 50,000 travelers, nine airline owners, and one administrator. Flights
are synthetic direct routes; timestamps use UTC throughout the existing app.
Airport timezone metadata does not change displayed timestamp semantics.

## Completed deployment

Applied on `201.18.216.184`. Final database verification passed after services
restarted, and all 21 application checks passed through the gateway: traveler,
administrator, all nine airline owners and ownership access, and five search/
seat-inventory scenarios in both cabins. The 21,216 instances include 9,984
overnight flights and 128 final-day flights arriving in 2027. All 2,545,920 seats
are available; booking, payment, and ancillary records remain empty.

Measured data plus indexes: **574,914,560 bytes** (about 575 MB / 548 MiB).
All nine database volumes together use 2,898,767,872 allocated bytes, including
MySQL internal files. The filesystem had 88,243,208,192 bytes free when measured.

Local evidence: `seed_future-verification.json`, `seed_future-smoke.json`, and
`seed_future-storage.json`. Backups and credentials remain only on the server.

## Commands on the server

```bash
# Read-only preview; does not create the run directory.
python3 /root/seed_future.py

# Apply, or resume the same interrupted run.
python3 -u /root/seed_future.py --apply --directory /root/future-seed-20261015

# Verify persisted database counts and relationships.
python3 /root/seed_future.py --verify --directory /root/future-seed-20261015

# Test three login roles and domestic/international/overnight/boundary searches.
# Run after all restarted services have registered with Eureka/the gateway.
python3 /root/seed_future.py --smoke --directory /root/future-seed-20261015
```

The seed refuses nonempty databases except the exact unmodified bootstrap admin
created by `DataInitializationComponent`. That account is backed up and replaced
with the seeded admin at the same `admin@gmail.com` email, preventing startup
from creating a second administrator. Its password becomes the generated seed
password. Airline owners use `seed.owner.<country-code>@example.invalid`;
travelers use `seed.traveler.00001@example.invalid` through
`seed.traveler.50000@example.invalid`.

`credentials.json` in the run directory holds the shared traveler password and
separate owner/admin passwords. The directory is mode 0700 and files are mode
0600. Credentials and JWTs are never included in verification reports.

## Recovery and reports

Before insertion, the script stops previously running application services and
backs up all nine databases into `<container>-before.sql`. On an insertion or
verification failure, services remain stopped. Resume with the same `--apply`
command; the script compares existing records against regenerated expected rows
before appending more data. Do not allow concurrent database writes during resume.

Alternatively, with writers still stopped, restore each matching backup through
that container's MySQL client and then restart the services listed in
`manifest.json`. Do not restore only one database of a partially seeded dataset.

After verification the script clears Redis and restarts the previously running
services. Reports are `manifest.json`, `verification.json`, `smoke.json`, and
`mysql-errors.log` in the run directory. Information-schema storage figures may
lag inserts; refresh MySQL table statistics before recording final usage.

Smoke checks perform real logins, updating login timestamps. They retrieve
flights and seats without creating bookings, payments, or ancillary records.
Because the app excludes past departures, first-day smoke checks must run before
the October 15 departures; database verification remains valid afterward.
