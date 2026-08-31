# Payment initiation

Booking-service initiates payments through `POST /internal/payments/initiate`.
The public `/api/payments/initiate` endpoint has been removed. The gateway routes
only `/api/payments/**` to payment-service, so it does not expose this internal
endpoint. Keep payment-service reachable only within the trusted service network,
as with its existing internal reconciliation endpoint.

There is one payment per booking. Repeating the same pending request returns its
existing payment and Stripe Checkout Session. Requests with changed user, amount,
or provider, and requests for terminal payments, return a conflict. Concurrent
requests claim the unique booking ID before contacting Stripe; the loser reads
the winning payment after its own transaction has rolled back.

For existing databases, run `db/payment-booking-uniqueness.sql` before deploying.
First resolve any duplicate records and their Stripe sessions. The script does
not delete payment history and the constraint fails if duplicates remain. New
schemas receive the same named unique constraint from the Payment entity. Do not
rely on Hibernate `ddl-auto: update` to repair existing duplicates.

Checked failures during checkout creation roll back the payment transaction.
Verification keeps retryable or processing Stripe PaymentIntents pending;
`succeeded` confirms payment and `canceled` publishes a terminal failure.

Focused tests (from the repository root):

```powershell
mvn -o -f microservices/pom.xml -pl services/payment-service,services/booking-service -am test '-Dtest=Payment*Test,Booking*Test,TicketServiceImplTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The initiation integration tests use H2 with real JPA transactions and uniqueness
checks, and mock Stripe and user-service calls. They cover checked-exception
rollback, repeated requests, and two concurrent inserts. They do not require
running Kafka, MySQL, Stripe, Eureka, or the config server.
