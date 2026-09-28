# API Examples

Use the API gateway at `http://localhost:5000` for local requests. Send `Content-Type: application/json` for JSON bodies and `Authorization: Bearer <token>` for protected endpoints. The gateway supplies the user identity headers to backend services. See [Security](../README.md#7-security) for gateway access rules.

IDs below are illustrative; replace them with IDs from your seeded data. Search with a future departure date that has available flights.

## Register — `POST /auth/signup`

```json
{
  "fullName": "John Smith",
  "email": "john@example.com",
  "password": "secret123",
  "phone": "+919876543210",
  "role": "ROLE_CUSTOMER"
}
```

Returns **200 OK** with an `AuthResponse` containing `jwt`, `title`, `message`, and `user`. Public registration rejects `ROLE_SYSTEM_ADMIN`; an already registered email returns **409 Conflict**.

## Login — `POST /auth/login`

```json
{
  "email": "john@example.com",
  "password": "secret123"
}
```

Returns **200 OK** with an `AuthResponse`. Use its `jwt` value as the bearer token. Invalid credentials return **401 Unauthorized**.

## Search flights — `GET /api/flights/search`

Required query parameters:

```text
?departureAirportId=1
&arrivalAirportId=2
&departureDate=2026-12-15
&passengers=2
&cabinClass=ECONOMY
```

Optional query parameters:

| Parameter | Example | Meaning |
|---|---|---|
| `airlines` | `1,2` | Comma-separated numeric airline IDs |
| `alliance` | `star` | Alliance filter: `any`, `star`, `oneworld`, `skyteam`, or full alliance name |
| `minPrice` | `1000` | Minimum fare price |
| `maxPrice` | `10000` | Maximum fare price |
| `departureTimeRange` | `MORNING` | `MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`, or `any` |
| `arrivalTimeRange` | `EVENING` | Same time-window options as departure |
| `maxDuration` | `180` | Maximum duration in minutes |
| `sortBy` | `price` | `departure`, `arrival`, `duration`, or `price` |
| `sortOrder` | `ASC` | `ASC` or `DESC` |
| `page` | `0` | Zero-based page index |
| `size` | `20` | Page size |

Returns **200 OK** with a page of `FlightInstanceResponse` objects. See the pagination example below.

## Create a booking — `POST /api/bookings`

Requires a bearer token and an `Idempotency-Key: <UUID>` header. Missing or invalid keys return **400 Bad Request**. Reuse the same key and request body when retrying the same booking operation. Reusing a key with a different booking request returns **409 Conflict**.

After a timeout, retry with the original key: background recovery can finish seat reservation and checkout, and the retry returns the saved checkout response. A definitive seat conflict returns **409 Conflict** and retains a cancelled booking attempt; use a new key for a new selection.

```json
{
  "flightInstanceId": 101,
  "flightId": 10,
  "fareId": 5,
  "cabinClass": "ECONOMY",
  "ancillaryIds": [1, 2],
  "mealIds": [3],
  "contactInfo": {
    "email": "john@example.com",
    "phone": "+919876543210"
  },
  "passengers": [
    {
      "firstName": "John",
      "lastName": "Smith",
      "email": "john@example.com",
      "phone": "+919876543210",
      "dateOfBirth": "1990-05-15",
      "gender": "MALE",
      "seatInstanceId": 201,
      "nationality": "American"
    }
  ]
}
```

Choose a flight instance belonging to `flightId`, a matching fare and cabin, and available seats from that departure. Ancillaries and meals must be valid for the selection; omit these lists or use empty lists when no add-ons are needed. `tripType` and passenger `passportNumber` are not fields in the current request DTOs.

Returns **201 Created** with `PaymentInitiateResponse`. Its fields include `bookingId`, `paymentId`, `gateway`, `transactionId`, `amount`, `description`, `checkoutUrl`, `stripeCheckoutUrl`, `message`, and `success`. Redirect the customer to `checkoutUrl` to complete Stripe Checkout.

## Resume checkout — `POST /api/bookings/{id}/checkout`

Requires a bearer token and no request body. Returns **200 OK** with `PaymentInitiateResponse` for the authenticated user's pending booking. A booking with an expired hold or a booking/payment that is no longer pending returns **409 Conflict**.

## Verify payment — `POST /api/payments/verify`

Requires a bearer token. To verify the authenticated user's stored Checkout Session, send:

```json
{
  "bookingId": 123
}
```

The service checks ownership and retrieves the persisted Checkout Session from Stripe. An unpaid session leaves the payment pending; verification does not expire it.

The endpoint also supports verification by PaymentIntent ID:

```json
{
  "stripePaymentIntentId": "pi_..."
}
```

This form retrieves the PaymentIntent and resolves the payment using its `payment_id` metadata. Pending Stripe statuses leave the payment pending. A cancelled PaymentIntent marks a pending payment as failed. When `bookingId` is present, the controller uses Checkout Session verification instead.

Both forms return **200 OK** with a `PaymentDto` on successful verification. A newly successful payment publishes `payment.completed`, triggering asynchronous booking confirmation, seat confirmation, and confirmation email delivery. Payment verification can return before booking confirmation finishes.

## Read booking and confirmation status

Requires a bearer token:

| Method | Endpoint | Response |
|---|---|---|
| GET | `/api/bookings/{id}` | `BookingResponse` for the authenticated user's booking |
| GET | `/api/bookings/{id}/confirmation-status` | Confirmation progress for the authenticated user's booking |
| GET | `/api/bookings/user/history` | List of the authenticated user's bookings |
| GET | `/api/bookings/user/history/page?tab=all&page=0&size=10` | Paginated booking history; `tab` accepts `all`, `upcoming`, `past`, or `cancelled` |
| PATCH | `/api/bookings/{id}/cancel` | Updated `BookingResponse`; no request body |

The confirmation-status response contains `bookingId`, `status`, `paymentReceived`, `ticketIssued`, `ticketCount`, and `confirmed`. Poll this endpoint after payment to wait for completed booking confirmation. Confirmed bookings cannot currently be cancelled because refunds are not supported.

## Response shapes

### Paginated list

Endpoints returning Spring Data `Page` use `number` and `size` for the page index and page size. This is a partial example with an empty result; additional pagination metadata may be present:

```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 20
}
```

### Authentication error

The auth controller's exception handler returns the following shape. This example represents a rejected login:

```json
{
  "timestamp": "2026-10-22T00:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid email or password",
  "path": "/auth/login"
}
```

Error bodies are not standardized across all services and the gateway. Other endpoints may return different fields or omit exception messages; do not assume the auth error shape applies to every endpoint.
