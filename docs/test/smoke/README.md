# VPS smoke tests

`vps_smoke.py` is a Python standard-library harness for the authorized VPS
`root@201.18.216.184`. It uses the gateway at `127.0.0.1:5000`, Docker tooling,
and MailHog at `127.0.0.1:8025`. No dependencies need installing on the server.
It creates dedicated records through application APIs; cleanup also uses APIs.
It does not patch applications, change configuration, restart containers, or
write directly to databases.

## Repeat a run

Copy the script to a private VPS directory with SSH/SCP. Supply an existing
super-admin login through a private JSON file containing `email` and `password`,
and set `ADMIN_CREDENTIAL_FILE` to that file's absolute path. Alternatively,
use `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables. Do not commit
credentials or private state.

Run on the VPS, choosing a new private directory for every run:

```sh
export ADMIN_CREDENTIAL_FILE=/root/private/smoke-admin.json
python3 /root/airline-smoke/vps_smoke.py prepare --directory /root/airline-smoke/run-new
```

If manual departure creation fails because a schedule is required, continue
the same run using:

```sh
python3 /root/airline-smoke/vps_smoke.py scheduled-fixture --directory /root/airline-smoke/run-new
```

That phase tests normal one-day recurrence creation. If recurrence generation
fails, it creates an empty recurrence through the API, updates its operating
day, and creates a departure with the real schedule ID. The original failures
remain in the report. It refuses to create the departure twice.

Run additional catalog and authorization checks:

```sh
python3 /root/airline-smoke/vps_smoke.py supplemental --directory /root/airline-smoke/run-new
```

Before valid booking/payment mutations, the harness verifies the deployed
Stripe key starts with `sk_test_` and mail is directed to MailHog. It stops those
mutations if the sandbox cannot be verified. It never prints keys or tokens.

When checkout creation succeeds, its URL is stored in `private-state.json`.
Use the deployed browser to complete **Stripe test checkout only**. Then run:

```sh
python3 /root/airline-smoke/vps_smoke.py verify-payment --directory /root/airline-smoke/run-new
```

The payment phase checks confirmation, tickets, booked seats, history, matching
MailHog mail, and repeat verification when the provider ID is available.
Browser testing remains a separate live UI pass: landing/login, role routing,
session reload/logout, traveler search/fare/booking, and owner/admin management.
Do not count API checks as browser coverage.

Always finish with:

```sh
python3 /root/airline-smoke/vps_smoke.py cleanup --directory /root/airline-smoke/run-new
```

Cleanup cancels unpaid bookings and removes tracked fixtures in dependency
order. Confirmed bookings and their dependency chains remain because refunds
are unsupported. User accounts remain because there is no user-delete API.
Report those records rather than deleting database rows.

## Results and limitations

- `results.json` contains credential-free assertions and tracked IDs.
- `private-state.json` contains test credentials/tokens and stays private on the
  VPS (directory mode 0700; new files mode 0600).
- Exit `0` means every recorded assertion passed. Exit `1` means failures or
  blocked coverage remain; it is expected when a known defect is reproduced.
- Missing business-service health instrumentation is BLOCKED coverage, not
  proof that a service is down. Gateway reads and real business assertions
  independently establish tested behavior.
- Runs are sequential smoke checks, not a load test or exhaustive security audit.

