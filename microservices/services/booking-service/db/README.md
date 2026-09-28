# Booking checkout claims

## Durable seat reservations

Run `booking-reservation.sql` once against `airline_booking_db` before deploying
this version. It adds nullable reservation state, the original seat IDs, a lease,
a retry timestamp and an index. Existing attempts retain NULL state: their seats
were already reserved by the previous flow, so no backfill or re-reservation runs.

Deploy seat-service before booking-service. The seat change checks cancellation
after acquiring seat locks under READ COMMITTED isolation. This prevents a late
reservation from undoing a release after waiting for those same seats.

New `POST /api/bookings` requests require a UUID `Idempotency-Key`. The booking,
passengers, priced payment request and PENDING reservation intent commit together.
Seat reservation runs with no booking transaction, followed by a short transaction
marking the reservation RESERVED. Only then can payment initiation begin, outside
the booking transaction as before. Reuse the same key and payload after a timeout.
Seat-service deduplicates reservation/capacity updates by booking ID.

Booking-service disables Open EntityManager in View (`spring.jpa.open-in-view=false`).
Keep it disabled in external configuration too: a request-scoped EntityManager can
retain a physical connection across these transactions and reuse stale entity
state after cancellation. Response DTO mapping remains inside service transactions.

Reservation leases last 120 seconds and fence stale completion writes. The recovery
job scans at most 100 eligible attempts every 30 seconds (configurable with
`booking.reservation.recovery-delay-ms`). It resumes work after a process crash,
with a 30-second backoff after failures. It restores the reservation and resumes
checkout using the saved payment request, including crashes between reservation
completion and payment initiation. The client can retry its original request to
obtain the saved checkout response. Existing booking expiry reconciliation still
handles expired checkout sessions after the hold expires.

A definitive seat conflict retains a CANCELLED booking and RELEASE_PENDING intent
instead of rolling back the provisional booking. The recovery job releases any
owned seats and installs seat-service's cancellation tombstone. Cancelled, deleted
or expired provisional bookings follow the same durable release path, including
when cancellation races an in-flight reservation. Seat IDs survive booking deletion
in the attempt record. Ambiguous remote failures remain PENDING and retryable.

Drain old booking instances during rollout; old code cannot recover the new
reservation intents. Do not roll booking-service back while new reservation work
is outstanding. The internal legacy creation methods retain their original
transaction/rollback behavior but are no longer reachable through POST creation.

## Payment confirmation transaction

Payment completion reads the pending booking's lookup IDs in a short read-only
transaction, then calls flight, pricing and user services with no database
transaction or row lock open. OSIV must remain disabled. Missing enrichment keeps
the existing notification fallbacks and does not prevent confirmation.

After enrichment, a separate transaction locks and reloads the booking, rechecks
that it is PENDING, and issues tickets and confirms payment together. Concurrent
duplicates produce one set of tickets and one confirmation publication;
cancellation or expiry committed during enrichment wins the final status check.
The notification producer still runs in this final transaction so its lazy
collections remain available and synchronous failures roll back confirmation.
Kafka delivery and database commit retain their existing semantics; this change
does not introduce an outbox. No schema migration is required.

## Ticket number index

Run `ticket-number-index.sql` against `airline_booking_db`. The October 22, 2026
test-host audit found 46,100 rows, 46,100 distinct ticket numbers, no nulls and no
duplicate groups. The generator's `generateUniqueTicketNumber` loop establishes
global uniqueness as the intended rule. A unique index also protects against two
transactions passing the existence check simultaneously. A collision fails the
transaction; it must be retried as a whole, not inside a failed transaction.

The migration reports duplicates and uses an online unique-index build, which
fails if duplicates exist or arrive concurrently. It never deletes or renumbers
tickets. It recognizes an equivalent existing unique index and is safe to reapply.
The five-second metadata-lock timeout avoids waiting indefinitely for busy tables;
retry a failed migration during a quieter interval. On the audited test host the
index has been applied and the lookup plan changed from `ALL` (42,468 estimated
rows) to `const` / `Using index` (one estimated row).

Fresh schemas receive the same named constraint from the `Ticket` mapping. Keep
the migration for existing schemas rather than relying on Hibernate schema update.

## Checkout claim migration

Before deploying, run `booking-idempotency.sql` if the attempt table does not
exist, then run `booking-checkout-claim.sql` once. The latter adds two nullable
columns to existing tables; existing attempts need no backfill.

An idempotent checkout commits a claim before calling payment-service and stores
the response in a separate transaction. During initiation, another request with
the same key receives HTTP 409 with an in-progress message and should retry using
the same key and payload. Once completed, retries return the saved response.
Confirmation can complete while initiation is waiting for payment-service.

Failures release only the current owner's claim, retaining the booking and its
original payment request for recovery. Claims expire after 120 seconds so a
process crash cannot strand a booking. A replacement owner fences stale response
writes and cleanup. If an overdue call overlaps recovery, payment-service's unique
booking claim and stable provider idempotency key prevent duplicate payments.
Expiry reconciliation defers while a checkout claim is active and uses the same
claim/finalization flow when recovering an abandoned attempt.
