# Airline — Microservices Platform

A production-grade **Airline Global Distribution System (GDS)** built with Spring Boot microservices, event-driven architecture, and cloud-native patterns. The platform covers the complete airline booking lifecycle — from flight search and seat selection through payment processing and passenger notifications.

---

## Table of Contents

1. [Features](#features)
2. [Architecture Overview](#architecture-overview)
3. [Services](#services)
4. [Data Flow](#data-flow)
5. [Technology Stack](#technology-stack)
6. [Design Patterns](#design-patterns)
7. [Security](#security)
8. [API Reference](#api-reference)
9. [Running the Project](#running-the-project)
10. [Smoke Test](#smoke-test)
11. [Configuration](#configuration)
12. [Known Limitations](#known-limitations)

---

## Features

### For Passengers (Customer-Facing)
- **Flight Search** — Multi-criteria search with filters: route, date, cabin class, airline, alliance, price range, departure/arrival time window, max duration, and sorting (price / departure / duration)
- **Seat Selection** — Interactive cabin-class seat map with availability status (window, aisle, middle, extra legroom)
- **Ancillary Services** — Add meals, insurance, and other add-ons at booking
- **Booking Management** — View, modify, and cancel bookings; track status in real time
- **Payment Processing** — Stripe Checkout integration with server-side PaymentIntent status verification
- **Booking Confirmation Email** — Production-grade HTML email with flight details, passenger list, ticket numbers, fare breakdown, baggage allowance, fare benefits, and payment receipt
- **Ticket Management** — E-ticket generation with unique ticket numbers per passenger
- **Booking History** — Full history of past and upcoming flights

### For Airline Administrators
- **Airline Management** — Register, approve, suspend, and ban airlines; manage status lifecycle
- **Aircraft Management** — Define aircraft types with seat capacities
- **Flight Management** — Create and manage master flights and recurring flight schedules
- **Flight Instance Management** — Manage specific departures with terminal, gate, and real-time status
- **Pricing & Fares** — Define fares per cabin class with full benefit matrix (refund, date change, lounge, meals, priority boarding)
- **Baggage Policies** — Cabin and check-in baggage rules linked to fares
- **Seat Maps** — Create seat map templates and assign per aircraft/cabin
- **Meal Management** — Create meal offerings and assign to specific flights
- **Insurance Products** — Define insurance coverage options
- **Ancillary Catalogue** — Manage ancillary service catalogue per airline
- **Booking Analytics** — Daily and monthly booking statistics with revenue tracking

### For System Administrators
- **Multi-Airline Support** — Isolated data per airline; RBAC protects cross-airline access
- **City & Airport Data** — Manage global location data with IATA codes, timezones, and geolocation
- **Centralized Configuration** — All service config driven from a Git-backed Config Server
- **Service Discovery** — Eureka-based dynamic service registration

---

## Architecture Overview

```
                                    ┌─────────────────────────────────────────────────────┐
                                    │                  CLIENT (Browser / Mobile)          │
                                    └───────────────────────────┬─────────────────────────┘
                                                                │ HTTPS :5000
                                    ┌───────────────────────────▼─────────────────────────┐
                                    │                    API GATEWAY                      │
                                    │         JWT Auth · Routing                          │
                                    └──┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬────────┘
                                       │   │   │   │   │   │   │   │   │   │   │
          ┌────────────────────────────┘   │   │   │   │   │   │   │   │   │   └──────────────────┐
          │         ┌──────────────────────┘   │   │   │   │   │   │   │   └─────────────┐        │
          ▼         ▼          ▼               ▼   ▼   ▼   ▼   ▼   ▼   ▼                ▼        ▼
   ┌────────────┐ ┌──────────┐ ┌────────────┐ ...                           ┌───────────┐ ┌──────────────┐
   │    User    │ │ Airline  │ │ Flight-Ops │                               │  Booking  │ │   Payment    │
   │  Service   │ │  Core   │ │  Service  │                               │  Service  │ │   Service    │
   └────────────┘ └──────────┘ └─────┬──────┘                               └─────┬─────┘ └──────┬───────┘
                                     │ Feign                                       │ Feign         │
                                     ├──► Pricing Service                          │               │
                                     ├──► Seat Service                             │               │
                                     └──► Airline Core                             │               │
                                                                                   ▼               ▼
                                    ┌──────────────────────────────────────────────────────────────────────┐
                                    │                         APACHE KAFKA                                  │
                                    │   booking.confirmed  │  payment.completed  │  payment.failed          │
                                    └──────┬───────────────────────────────────────────────────────────────┘
                                           │
                                    ┌──────▼──────────────────────────────────────────────────────────────┐
                                    │   Seat Service (mark seats BOOKED)  │  Notification Service (email)     │
                                    └─────────────────────────────────────────────────────────────────────┘

                     ┌─────────────────────────────────────────────────────────────────────────────────┐
                     │                         INFRASTRUCTURE                                          │
                     │    Eureka (Service Registry) ·  Config Server (Git-backed) · MySQL per service  │
                     └─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Services

### Infrastructure Services

| Service | Port | Role |
|---|---|---|
| **API Gateway** | 5000 (external) | Single entry point — JWT validation, routing, CORS |
| **Config Server** | 8888 | Centralized config pulled from Git for all services |
| **Service Registry** | 8761 | Eureka — service registration and discovery |
| **Kafka** | 9092 | Event streaming (KRaft mode — no Zookeeper) |
| **MySQL** | varies | Dedicated database per business service |

### Business Services

| Service | Port | Database | Kafka | Responsibility |
|---|---|---|---|---|
| **user-service** | 5001 | `airline_user` | — | Auth (signup/login), JWT, user profiles, RBAC |
| **airline-service** | 5002 | `airline_db` | — | Airline + aircraft management, alliance data |
| **location-service** | 5004 | `airline_location_db` | — | Cities, airports, IATA codes, geolocation |
| **flight-service** | 5005 | `airline_flight_db` | Producer | Flights, schedules, instances, advanced search |
| **seat-service** | 5007 | `airline_seat_db` | Consumer | Seat maps, cabin config, availability, seat pricing, reservations |
| **pricing-service** | 5006 | `airline_pricing_db` | — | Fares, baggage policies, fare rules |
| **ancillary-service** | 5008 | `airline_ancillary_db` | — | Meals, insurance, ancillary catalogue |
| **booking-service** | 5009 | `airline_booking_db` | Consumer + Producer | Bookings, passengers, tickets, booking events |
| **payment-service** | 5010 | `airline_payment_db` | Producer | Stripe Checkout, payment verification, expiry reconciliation |
| **notification-service** | 5011 | — | Consumer | SMTP email with Thymeleaf templates (MailHog by default) |

---

## Data Flow

### Flight Search

```
Client → API Gateway → flight-service
  │
  ├─ 1. Query all DB candidates via JPA Specification (Criteria API)
  │       Apply route, date, numeric airline IDs, passenger count,
  │       duration, and departure/arrival time filters
  │
  ├─ 2. Apply alliance filter when requested
  │       └─[Feign]→ airline-service  (fetch airline by ID)
  │
  ├─ 3. When cabin class is requested, check seats and fares
  │       ├─[Feign]→ seat-service  (resolve cabin and count AVAILABLE seats)
  │       │           Exclude candidates with insufficient seats for passengers
  │       └─[Feign]→ pricing-service  (lowest fare for flight and cabin)
  │                   Exclude candidates without a valid fare
  │       Cache airline, cabin, and fare lookups within the request
  │
  ├─ 4. Apply price range filter and sort all matching candidates
  │       Sort by departure, arrival, duration, or price
  │
  └─ 5. Paginate filtered, sorted matches and enrich the selected page
          Return Page<FlightInstanceResponse>; totalElements counts all matches
```

### Booking & Payment

```
Client → API Gateway → booking-service
  │
  ├─ 1.  Validate flight      [Feign] → flight-service
  ├─ 2.  Validate seats        [Feign] → seat-service
  ├─ 3.  Validate ancillaries  [Feign] → ancillary-service
  ├─ 4.  Fetch fare            [Feign] → pricing-service
  ├─ 5.  Create Booking (status: PENDING)
  ├─ 6.  Reserve selected seats for booking [Feign] → seat-service
  │       Set holdExpiresAt to 30 minutes from now
  ├─ 7.  Initiate payment      [Feign] → payment-service → Stripe Checkout
  └─ 8.  Return payment URL to client
         │
         │  [Client completes payment on gateway]
         │
  ┌──────▼──────────────────────────────────────────────────────────┐
  │  Client requests POST /api/payments/verify                      │
  │  → Fetch PaymentIntent from Stripe; check status is succeeded   │
  │  → Publish PaymentCompletedEvent ─────────────► Kafka           │
  └──────────────────────────────────────────────────────────────────┘
         │
         ▼  [Kafka: payment.completed]
  ┌──────────────────────────────────────────────────────────────────┐
  │  booking-service (PaymentEventListener)                         │
  │  → Update Booking status to CONFIRMED                           │
  │  → Fetch FlightInstance, Fare, User details [Feign]             │
  │  → Publish BookingConfirmedEvent ──────────────► Kafka          │
  └──────────────────────────────────────────────────────────────────┘
         │
         ▼  [Kafka: booking.confirmed]  (two parallel consumers)
  ┌──────────────────┐       ┌──────────────────────────────────────┐
  │   seat-service   │       │        notification-service          │
  │  Mark seats as   │       │  Send confirmation email (HTML)      │
  │  BOOKED          │       │                                      │
  └──────────────────┘       └──────────────────────────────────────┘
```

Seats are reserved before payment initiation. If the booking transaction rolls back, it attempts to release those reservations through `seat-service`.

Every minute, `BookingExpiryJob` reconciles overdue pending bookings with `payment-service`, which checks the Stripe Checkout Session and attempts to expire an open session. A successful payment confirms the booking through the payment-completion handler. A failed or cancelled payment causes expiry cancellation: release seats directly through `seat-service`, cancel tickets, and mark the booking `CANCELLED`. Pending or unresolved payments remain pending; reconciliation or seat-release failures are retried on a later run.

Explicit cancellation of a pending booking and `payment.failed` handling cancel tickets, mark the booking `CANCELLED`, and publish `booking.cancelled` after commit. The seat-service listener consumes that event and releases the booking's seats. Confirmed bookings cannot currently be cancelled because refunds are not supported.

### Booking Confirmation Email Content

The HTML email sent after a confirmed booking includes:

- **Header** — Airline logo, "Booking Confirmed ✓" badge, personalised greeting
- **Booking Reference** — Large, prominent, monospaced reference number
- **Flight Route Card** — Departure/arrival IATA codes, city names, times, date, flight number, duration, terminal, gate, cabin class, aircraft model
- **Passenger Table** — Per passenger: name, adult/child type, ticket number, passport number, nationality, and any special requirements (wheelchair, dietary)
- **Baggage Allowance** — Cabin and check-in allowance from the fare's baggage policy
- **Fare Benefits** — Priority boarding, lounge access, complimentary meals, date change, refund entitlement — shown as ✓ / ✗
- **Payment Summary** — Base fare (× passengers), taxes, seat fees, ancillaries, meals, **total paid**, transaction ID, provider reference, payment gateway, payment date
- **Web Check-In CTA** — Button linking to the check-in portal
- **Important Information** — Check-in open window, airport deadline, ID requirements, refund/change policy
- **Footer** — Support email, phone, legal notice

---

## Technology Stack

### Backend Framework

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot | 4.1.0 | Base application framework |
| Spring Cloud | 2025.1.2 | Microservices toolkit (Config, Eureka, Gateway, Feign) |
| Spring Data JPA | managed | ORM with Criteria API support |
| Spring Security | managed | Authentication and RBAC |
| Spring Kafka | managed | Kafka producer/consumer integration |
| Spring Mail | managed | JavaMailSender (email sending) |
| Thymeleaf | managed | HTML email template rendering |

### Infrastructure

| Technology | Version | Purpose |
|---|---|---|
| Apache Kafka | 4.3.1 | Async event streaming (KRaft — no Zookeeper) |
| MySQL | 8.0 | Primary relational database (one DB per service) |
| Eureka Server | Spring Cloud | Service discovery and registration |
| Spring Cloud Config | Spring Cloud | Git-backed centralised configuration |
| Spring Cloud Gateway | Spring Cloud | API gateway with WebMVC routing |

### Inter-Service Communication

| Technology | Purpose |
|---|---|
| OpenFeign | Declarative HTTP client for inter-service calls |

### Security

| Technology | Purpose |
|---|---|
| JJWT (io.jsonwebtoken) | JWT creation, parsing, and validation |
| BCrypt | Password hashing |
| Spring Security | Method-level and URL-based authorization |

### Payment Gateways

| Gateway | Purpose |
|---|---|
| Stripe | Hosted Checkout sessions and server-side PaymentIntent verification |

### Notifications

| Technology | Purpose |
|---|---|
| MailHog (SMTP 1025, web UI 8025) | Default local email capture |
| Thymeleaf (HTML) | Responsive HTML email templates with inline CSS |

### Build & Deployment

| Tool | Purpose |
|---|---|
| Maven (multi-module) | Build tool with parent POM for version management |
| Google Jib | Build Docker images via Maven without a Dockerfile |
| Docker Compose | Local and production container orchestration |
| Spring Boot DevTools | Live reload during development |
| Lombok | Compile-time boilerplate generation |

---

## Design Patterns

### Microservices Patterns

| Pattern | Where Used |
|---|---|
| **API Gateway** | Single entry point — all traffic routed through `api-gateway` |
| **Service Registry** | Eureka — services register themselves; gateway resolves URLs dynamically |
| **Config Server** | All service configs in a Git repo; services pull on startup |
| **Saga (choreography)** | Booking → Payment → Seat Reservation → Notification via Kafka events |

### Data Patterns

| Pattern | Where Used |
|---|---|
| **Repository Pattern** | `JpaRepository` for all CRUD; `JpaSpecificationExecutor` for dynamic queries |
| **Specification Pattern** | `FlightInstanceSpecification` — Criteria API for complex flight search filters |
| **DTO / Mapper** | Request/Response DTOs in `common-lib`; Mapper classes per service |
| **Denormalization** | `Fare.cabinClass` stored on Fare (avoids join with seat-service during search) |
| **Bulk Endpoints** | `POST /bulk` on all resource controllers — reduces network round-trips |
| **Per-Request Cache** | `HashMap` caches inside a single request — avoids N+1 in enrichment loops |

### Event-Driven Patterns

| Pattern | Where Used |
|---|---|
| **Event-Driven Messaging** | Kafka topics carry state-change events (`BookingConfirmedEvent`, `PaymentCompletedEvent`) |
| **Pub/Sub** | `booking.confirmed` consumed by both `seat-service` and `notification-service` |
| **Event Enrichment** | `BookingEventProducer` enriches event with user, flight, fare, baggage data before publishing |
| **Consumer Groups** | Each service has its own group ID — each gets its own copy of every message |

### Cross-Cutting Concerns

| Concern | Approach |
|---|---|
| Authentication | JWT validated at gateway; userId/roles forwarded as headers to backend |
| Authorisation | Role checked at gateway (URL-level) and service (method-level) |
| Error handling | `ResourceNotFoundException`, `PaymentException`, `UserException` in common-lib |
| Observability | Actuator dependency included in `config-server` and `service-registry` |
| Logging | Slf4j + `@Slf4j` (Lombok) throughout |

---

## Security

### Roles

| Role | Permissions |
|---|---|
| `ROLE_SYSTEM_ADMIN` | Create/manage airlines, airports, cities; approve/suspend/ban airlines |
| `ROLE_AIRLINE_OWNER` | Manage own airline's flights, schedules, fares, seats, ancillaries |
| `ROLE_CUSTOMER` | Search flights, make bookings, view profile, manage own bookings |

### JWT Authentication Flow

```
1. POST /auth/login  { email, password }
        │
        ▼
   user-service validates credentials
        │
        ▼
   Returns JWT token (payload: userId, email, role, expiry)
        │
        ▼
   Client sends: Authorization: Bearer <token>
        │
        ▼
   API Gateway validates signature + expiry
   Extracts claims → forwards as headers:
     X-User-Id, X-User-Email, X-User-Roles
        │
        ▼
   Backend services trust these headers
   (JWT already verified at gateway)
```

### Endpoint Protection

| Path | Access |
|---|---|
| `POST /auth/signup`, `POST /auth/login` | Public |
| `POST /api/airlines`, `POST /api/airports`, `POST /api/cities` | ROLE_SYSTEM_ADMIN |
| `GET /api/flights/search`, all GET endpoints | Any authenticated user |
| `POST /api/bookings`, `GET /api/bookings/user/**` | ROLE_CUSTOMER |
| `GET /api/bookings/airline/**` | ROLE_AIRLINE_OWNER / ROLE_SYSTEM_ADMIN |

---

## API Reference

### Auth — `POST /auth/signup`
```json
{
  "fullName": "John Smith",
  "email": "john@example.com",
  "password": "secret123",
  "phone": "+919876543210",
  "role": "ROLE_CUSTOMER"
}
```

### Flight Search — `GET /api/flights/search`
```
?departureAirportId=1
&arrivalAirportId=2
&departureDate=2026-03-15
&passengers=2
&cabinClass=ECONOMY
&airlines=1,2               (optional, comma-separated numeric airline IDs)
&alliance=star              (optional)
&minPrice=1000              (optional)
&maxPrice=10000             (optional)
&departureTimeRange=MORNING (optional: MORNING/AFTERNOON/EVENING/NIGHT)
&maxDuration=180            (optional, minutes)
&sortBy=price               (optional: departure/arrival/duration/price)
&sortOrder=ASC
&page=0&size=20
```

### Create Booking — `POST /api/bookings`
```json
{
  "flightInstanceId": 101,
  "flightId": 10,
  "fareId": 5,
  "cabinClass": "ECONOMY",
  "tripType": "ONE_WAY",
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
      "passportNumber": "AB1234567",
      "nationality": "American"
    }
  ]
}
```
**Returns:** `PaymentInitiateResponse` with `checkoutUrl` for Stripe Checkout

### Verify Payment — `POST /api/payments/verify`
```json
{"stripePaymentIntentId":"pi_..."}
```
The service retrieves the PaymentIntent from Stripe and reads its status and payment metadata. Pending statuses leave the payment pending. A newly successful payment publishes `payment.completed`, triggering asynchronous booking confirmation, seat confirmation, and confirmation email delivery.

### Common Response Shapes

**Paginated list:**
```json
{
  "content": [ ... ],
  "totalElements": 120,
  "totalPages": 6,
  "pageNumber": 0,
  "pageSize": 20
}
```

**Error:**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Booking not found with ID: 99"
}
```

---

## Running the Project

### Prerequisites
- Java 25
- Maven 3.9+
- Docker + Docker Compose
- Git

### Quick Start (Docker Compose)

```bash
# 1. Clone the repository
git clone <repo-url>
cd <repo-directory>

# 2. Set required environment variables (copy and fill in)
cp docker-compose/.env.example docker-compose/.env
# Edit docker-compose/.env before continuing:
# AIRLINE_IMAGE_TAG=1.0.0 (matches the current Jib image tags)
# STRIPE_API_KEY=<your Stripe API key>
# STRIPE_CALLBACK_BASE_URL=http://localhost:5173 (or your frontend URL)

# 3. Build and install modules, then build service images into local Docker
# Keep Docker running for jib:dockerBuild.
mvn -f microservices/pom.xml clean install -DskipTests
mvn -f microservices/pom.xml \
  -pl cloud/config-server,cloud/service-registry,cloud/api-gateway,services/user-service,services/airline-service,services/location-service,services/flight-service,services/seat-service,services/pricing-service,services/ancillary-service,services/booking-service,services/payment-service,services/notification-service \
  compile com.google.cloud.tools:jib-maven-plugin:3.5.2:dockerBuild \
  -Djib.from.image=eclipse-temurin:25-jre -DskipTests

# 4. Start all services using the locally built images
docker compose --env-file docker-compose/.env -f docker-compose/docker-compose.yml up -d

# 5. Check container status
docker compose --env-file docker-compose/.env -f docker-compose/docker-compose.yml ps
```

Compose uses Docker images; building JARs alone does not update those images. The Jib command above builds the configured `andyy02/<service>:1.0.0` images locally using a Java 25 runtime. Keep `AIRLINE_IMAGE_TAG=1.0.0` for this source-build workflow.

To use published images instead, skip step 3, set `AIRLINE_IMAGE_TAG` to the intended published version, and run `docker compose --env-file docker-compose/.env -f docker-compose/docker-compose.yml pull` before step 4. The selected images must be available in the registry.

The API is available at `http://localhost:5000`.

### Local Development (individual services)

```bash
# Start infrastructure first
docker compose -f docker-compose/docker-compose.yml up -d kafka mysql eureka config-server

# Then start individual services from their directory
cd services/booking-service
mvn spring-boot:run
```

### Environment Variables

```env
# Email (Compose defaults: MailHog)
MAIL_HOST=mailhog
MAIL_PORT=1025
MAIL_FROM=notifications@airlines.test
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_SMTP_AUTH=false
MAIL_STARTTLS_ENABLE=false

# Payment — Stripe
STRIPE_API_KEY=sk_live_xxxxxxxxxxxxxxxxxxxxxxxx

```

Compose sends email to MailHog by default; view captured messages at [http://localhost:8025](http://localhost:8025). When running `notification-service` directly, its default SMTP host is `localhost` on port `1025`. For an external SMTP server, override the mail settings above; Compose reads `MAIL_PASSWORD` for the SMTP password.

JWT signing and validation currently use `JwtConstant.SECRET_KEY` in `user-service` and `api-gateway`. `JWT_SECRET` is not read by the current JWT code.

---

## Smoke Test

Start the local stack with MailHog and a Stripe **test** key, and have Node.js 20+ ready. Each registration run creates a new `ROLE_CUSTOMER` with a random username/email and the password `password`, then logs in. Update [the fixture](docs/test%20scripts/smoke/booking-smoke.example.json) with seeded airport IDs, a future flight date, and a cabin with available seats and a fare; optionally set `flightInstanceId`. See the [test scripts README](docs/test%20scripts/README.md) for both test types.

From the repository root in PowerShell:

```powershell
$env:SMOKE_AUTH_MODE = "register"
$env:STRIPE_API_KEY = "sk_test_..." # Same Stripe account as payment-service
node "docs/test scripts/smoke/booking-smoke.mjs" "docs/test scripts/smoke/booking-smoke.example.json"
```

Open the printed Checkout URL within 10 minutes. Use test card `4242 4242 4242 4242`, any future expiry, and any three-digit CVC. The script checks signup/login, search, the reserved seat, payment verification, confirmed booking/ticket, booked seat, history, and MailHog email. Success prints `SMOKE TEST PASSED` and exits `0`; failures exit `1`. Created accounts and bookings persist; success leaves the booking confirmed.

To use an existing account, set `SMOKE_AUTH_MODE=login`, `SMOKE_EMAIL`, and `SMOKE_PASSWORD`. Registration mode ignores these credential variables and always generates a fresh account. The backend authenticates by email.

Defaults are API `http://localhost:5000` and MailHog `http://localhost:8025`; override with `SMOKE_API_URL` and `SMOKE_MAILHOG_URL` (loopback URLs only). To run the script's isolated tests:

```powershell
node --test "docs/test scripts/smoke/booking-smoke.test.mjs"
```

To test the real booking flow with an in-memory database and mocked external services (JDK 25 and Maven required):

```powershell
& "./docs/test scripts/integration/run-booking-flow.ps1"
```

---

## Configuration

All service configuration is managed by the **Config Server** which pulls from:
```
https://github.com/developer407/gds-config
```

Each service bootstraps with:
```yaml
spring:
  config:
    import: optional:configserver:http://localhost:8888
```

`optional:` means if the Config Server is unavailable, the service falls back to its local `application.yaml`.

---

## Known Limitations

| Limitation | Impact | Workaround |
|---|---|---|
| No distributed transactions (2PC) | Partial failures require compensating actions | Saga choreography via Kafka; status-based compensation |
| Search evaluates all DB candidates before pagination | Memory use and sorting cost grow with candidate count; alliance, cabin availability, and fare checks make external calls that add latency and depend on downstream services | Narrow route/date filters; per-request caches reuse airline, cabin, and fare lookups, while seat availability is fetched per candidate |
| Config Server is a single point of failure | All services need it on first start | `optional:` config import; local YAML fallback |
| Kafka is single broker | No replication in Docker Compose | Increase replicas in Kubernetes deployment |
| Notification failure is silent | Email may not be delivered | Dead-letter queue (DLQ) planned for Kafka |

---

## Project Structure

```
Airline System/
├── microservices/                   # Maven backend
│   ├── pom.xml                      # Parent POM — version management
│   ├── common-lib/                  # Shared payloads, enums, events, domain types
│   │   └── src/main/java/com/example/
│   │       ├── domain/              # Shared ancillary and baggage metadata
│   │       ├── embeddable/          # JPA embeddables
│   │       ├── enums/               # Shared enums
│   │       ├── event/               # Kafka event objects
│   │       └── payload/
│   │           ├── dto/             # Shared data transfer objects
│   │           ├── request/         # Inbound request DTOs
│   │           └── response/        # Outbound response DTOs
│   ├── cloud/                       # Infrastructure services
│   │   ├── api-gateway/             # Spring Cloud Gateway — routing + JWT
│   │   ├── config-server/           # Spring Cloud Config Server
│   │   └── service-registry/        # Eureka Server
│   └── services/                    # Business microservices
│       ├── user-service/
│       ├── airline-service/
│       ├── location-service/
│       ├── flight-service/
│       ├── seat-service/
│       ├── pricing-service/
│       ├── ancillary-service/
│       ├── booking-service/
│       ├── payment-service/
│       └── notification-service/
├── docker-compose/                  # Deployment and local infrastructure
│   ├── .env.example
│   ├── docker-compose.yml
│   └── docker-compose.dev.yml
├── airline-system-config/           # Central service YAML configuration
├── airline-system-frontend/         # Frontend application
├── docs/                            # Supporting documentation
└── README.md
```

---

*Built with Spring Boot 4.1.0 · Spring Cloud 2025.1.2 · Apache Kafka 4.3.1 · MySQL 8.0*
