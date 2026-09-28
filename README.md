# Airline — Microservices Platform

An **Airline Global Distribution System (GDS)** built with Spring Boot microservices, event-driven architecture, and cloud-native patterns. The platform covers the airline booking lifecycle — from flight search and seat selection through payment processing and passenger notifications. Current reliability and deployment constraints are documented in [Known Limitations](#12-known-limitations).

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Features](#2-features)
3. [Services](#3-services)
4. [Data Flow](#4-data-flow)
5. [Technology Stack](#5-technology-stack)
6. [Design Patterns](#6-design-patterns)
7. [Security](#7-security)
8. [API Reference](#8-api-reference)
9. [Running the Project](#9-running-the-project)
10. [Tests](#10-tests)
11. [Configuration](#11-configuration)
12. [Known Limitations](#12-known-limitations)
13. [Project Structure](#13-project-structure)

---

## 1. Architecture Overview

![Airline system architecture](docs/architecture/airline%20system%20architecture.png)

<details>
<summary>Text architecture diagram</summary>

```text
                         ┌─────────────────────────────┐
                         │ Browser / Frontend          │
                         │ Local dev: localhost:5173   │
                         └──────────────┬──────────────┘
                                        │ HTTP :5000
                         ┌──────────────▼──────────────┐
                         │ API Gateway                 │
                         │ JWT validation, CORS, routes │
                         └──────────────┬──────────────┘
                                        │ HTTP + service discovery
            ┌───────────────────────────┴┬────────────────────────────┐
            ▼                            ▼                            ▼
┌───────────────────────┐    ┌───────────────────────┐    ┌───────────────────────┐
│ Identity & catalog    │    │ Flight & inventory    │    │ Booking & payment     │
│                       │    │                       │    │                       │
│ user-service     5001 │    │ flight-service   5005 │    │ booking-service  5009 │
│ airline-service  5002 │    │ pricing-service  5006 │    │ payment-service  5010 │
│ location-service 5004 │    │ seat-service     5007 │    │                       │
│ ancillary-service:5008│    │                       │    │                       │
└───────────────────────┘    └───────────────────────┘    └───────────┬───────────┘
                                                                      │ Stripe API
                                                                      ▼
                                                          ┌───────────────────────┐
                                                          │ Stripe Checkout       │
                                                          └───────────────────────┘

  Inter-service HTTP calls use OpenFeign. Key dependencies:
    flight-service  ──► airline, location, seat, pricing
    booking-service ──► flight, seat, ancillary, pricing, payment, user, airline
    payment-service ──► user

  ASYNCHRONOUS EVENTS — Apache Kafka :9092
  ──────────────────────────────────────────────────────────────────────────
  Producer             Topic                      Consumers

  flight-service  ──►  flight-instance-created ──► seat-service
  payment-service ──►  payment.completed       ──► booking-service
                  └─►  payment.failed          ──► booking-service
  booking-service ──►  booking.cancelled       ──► seat-service
                  └─►  booking.confirmed       ──► seat-service
                                               └─► notification-service :5011
                                                    │ SMTP
                                                    ▼
                                               MailHog / SMTP server

  DATA & INFRASTRUCTURE
  ──────────────────────────────────────────────────────────────────────────
  MySQL         One database per business service, except notification
  Redis :6379   Shared cache infrastructure for services using Redis
  Eureka :8761  Service registration and discovery for gateway / services
  Config :8888  Remote configuration from the configured Git repository
                (combined with local YAML and environment variables)

  Service groups above are visual groupings, not separate deployments.
  Ports identify service listeners; Compose exposes the gateway for API use.
```

</details>

---

## 2. Features

### 2.1. For Passengers (Customer-Facing)
- **Flight Search** — Multi-criteria search with filters: route, date, cabin class, airline, alliance, price range, departure/arrival time window, max duration, and sorting (price / departure / duration)
- **Seat Selection** — Interactive cabin-class seat map with availability status (window, aisle, middle, extra legroom)
- **Ancillary Services** — Add meals, insurance, and other add-ons at booking
- **Booking Management** — View, modify, and cancel bookings; track status in real time
- **Payment Processing** — Stripe Checkout integration with server-side PaymentIntent status verification
- **Booking Confirmation Email** — Production-grade HTML email with flight details, passenger list, ticket numbers, fare breakdown, baggage allowance, fare benefits, and payment receipt
- **Ticket Management** — E-ticket generation with unique ticket numbers per passenger
- **Booking History** — Full history of past and upcoming flights

### 2.2. For Airline Administrators
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

### 2.3. For System Administrators
- **Multi-Airline Support** — Manage isolated data per airline
- **City & Airport Data** — Manage global location data with IATA codes, timezones, and geolocation

---

## 3. Services

### 3.1. Infrastructure Services

| Service | Port | Role |
|---|---|---|
| **API Gateway** | 5000 (external) | Single entry point — JWT validation, routing, CORS |
| **Config Server** | 8888 | Centralized config pulled from Git for all services |
| **Service Registry** | 8761 | Eureka — service registration and discovery |
| **Kafka** | 9092 | Event streaming (KRaft mode — no Zookeeper) |
| **MySQL** | varies | Dedicated database per business service |

### 3.2. Business Services

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

## 4. Data Flow

### 4.1. Flight Search

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

### 4.2. Booking & Payment

The diagram shows a new booking submitted with an `Idempotency-Key`.

```
Client → API Gateway → POST /api/bookings → booking-service
  │
  ├─ 1. Validate a new booking (parallel Feign lookups, no DB transaction)
  │       ├─ flight-service: flight booking validation
  │       ├─ seat-service: cabin capacity, selected seats, and seat prices
  │       ├─ pricing-service: fare details
  │       ├─ ancillary-service: ancillary prices (when selected)
  │       └─ ancillary-service: meal prices (when selected)
  │       Join results and validate the combined booking selection
  │
  ├─ 2. Persist passengers and PENDING booking; calculate total
  │       Reserve seats [Feign] → seat-service
  │       Set holdExpiresAt to 30 minutes from now; commit booking
  │
  ├─ 3. Initiate payment after the booking transaction commits
  │       [Feign] → payment-service: POST /internal/payments/initiate
  │       → Create or reuse the booking's payment and Stripe Checkout Session
  │
  └─ 4. Return Checkout URL → client completes payment on Stripe
          │
          ▼
      Client → API Gateway → POST /api/payments/verify → payment-service
          ├─ bookingId: verify the stored Stripe Checkout Session
          └─ PaymentIntent ID: verify its status with Stripe
          │
          ▼  [Successful payment → Kafka: payment.completed]
      booking-service (PaymentEventListener)
          → Generate tickets and mark the PENDING booking CONFIRMED
          → Fetch flight, fare, and user details [Feign]
          → Publish BookingConfirmedEvent → Kafka: booking.confirmed
          │
          ├─ seat-service: mark reserved seats BOOKED
          └─ notification-service: send confirmation email (HTML)
             (independent Kafka consumer groups)
```

---

## 5. Technology Stack

| Area | Technologies | Purpose |
|---|---|---|
| Backend | Spring Boot 4.1.0, Spring Data JPA | Application framework and persistence |
| Microservices | Spring Cloud 2025.1.2, Eureka, Config, Gateway, OpenFeign | Discovery, configuration, routing, and service calls |
| Messaging | Apache Kafka 4.3.1, Spring Kafka | Event streaming and asynchronous processing |
| Database | MySQL 8.0 | Dedicated database per service |
| Security | Spring Security, JJWT, BCrypt | Authentication, authorization, JWT, and password hashing |
| Payments | Stripe | Hosted Checkout and server-side payment verification |
| Notifications | Spring Mail, Thymeleaf, MailHog | HTML email delivery and local email capture |
| Build & Deployment | Maven, Google Jib, Docker Compose | Dependency management, container images, and orchestration |
| Development | Spring Boot DevTools, Lombok | Live reload and boilerplate reduction |

---

## 6. Design Patterns

### 6.1. Microservices Patterns

| Pattern | Where Used |
|---|---|
| **API Gateway** | Single entry point — all traffic routed through `api-gateway` |
| **Service Registry** | Eureka — services register themselves; gateway resolves URLs dynamically |
| **Config Server** | All service configs in a Git repo; services pull on startup |
| **Saga (choreography)** | Booking → Payment → Seat Reservation → Notification via Kafka events |

### 6.2. Data Patterns

| Pattern | Where Used |
|---|---|
| **Repository Pattern** | `JpaRepository` for all CRUD; `JpaSpecificationExecutor` for dynamic queries |
| **Specification Pattern** | `FlightInstanceSpecification` — Criteria API for complex flight search filters |
| **DTO / Mapper** | Request/Response DTOs in `common-lib`; Mapper classes per service |
| **Denormalization** | `Fare.cabinClass` stored on Fare (avoids join with seat-service during search) |
| **Bulk Endpoints** | `POST /bulk` on all resource controllers — reduces network round-trips |
| **Per-Request Cache** | `HashMap` caches inside a single request — avoids N+1 in enrichment loops |

### 6.3. Event-Driven Patterns

| Pattern | Where Used |
|---|---|
| **Event-Driven Messaging** | Kafka topics carry state-change events (`BookingConfirmedEvent`, `PaymentCompletedEvent`) |
| **Pub/Sub** | `booking.confirmed` consumed by both `seat-service` and `notification-service` |
| **Event Enrichment** | `BookingEventProducer` enriches event with user, flight, fare, baggage data before publishing |
| **Consumer Groups** | Each service has its own group ID — each gets its own copy of every message |

---

## 7. Security

### 7.1. Roles

| Role | Permissions |
|---|---|
| `ROLE_SYSTEM_ADMIN` | Create/manage airlines, airports, cities; approve/suspend/ban airlines |
| `ROLE_AIRLINE_OWNER` | Manage own airline's flights, schedules, fares, seats, ancillaries |
| `ROLE_CUSTOMER` | Search flights, make bookings, view profile, manage own bookings |

### 7.2. JWT Authentication Flow

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

### 7.3. Endpoint Protection

Endpoints are grouped below by their intended access policy. Specific public and admin-only routes take precedence over broader resource paths.

| Method / path | Required access |
|---|---|
| `/auth/**`; `GET /api/flights/search`; `GET /api/airports`; `/api/airports/`; `GET /api/airlines/dropdown`; `GET /api/cabin-classes/aircraft/{aircraftId}`; `GET /api/fares/flight/{flightId}/cabin-class/{cabinId}` | Public |
| `POST /api/cities/**`; `POST /api/airports/**`; `GET /api/airlines`; `POST /api/airlines/{id}/approve`, `/api/airlines/{id}/suspend`, `/api/airlines/{id}/ban`; `GET /api/payments`; `/api/payments/` | ROLE_SYSTEM_ADMIN |
| `/api/users/**`; `POST /api/airlines` | Any authenticated user |
| `POST /api/bookings`; `POST /api/bookings/{id}/checkout`; `GET /api/bookings/{id}`; `/api/bookings/{id}/confirmation-status`; `GET /api/bookings/user/history`; `/api/bookings/user/history/page`; `PATCH /api/bookings/{id}/cancel`; `POST /api/payments/verify`; `/api/payments/batch/bookings` | ROLE_CUSTOMER |
| `/api/airlines/**`; `/api/aircrafts/**`; `/api/flights/**`, `/api/flight-instances/**`, `/api/flight-schedules/**`; `/api/cabin-classes/**`, `/api/seat-maps/**`, `/api/seats/**`, `/api/seat-instances/**`, `/api/flight-instance-cabins/**`; `/api/fares/**`, `/api/fare-rules/**`, `/api/baggage-policies/**`; `/api/meals/**`, `/api/ancillaries/**`, `/api/insurance-coverages/**`, `/api/flight-meals/**`, `/api/flight-cabin-ancillaries/**`; `GET /api/bookings/airline`; `/api/bookings/airline/page` | ROLE_AIRLINE_OWNER / ROLE_SYSTEM_ADMIN |

---

## 8. API Reference

Send `Authorization: Bearer <token>` for protected endpoints.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/auth/signup` | Register a customer or airline owner |
| POST | `/auth/login` | Obtain a JWT |
| GET | `/api/flights/search` | Search flights with filters and pagination |
| POST | `/api/bookings` | Create a booking and obtain a Stripe Checkout URL |
| POST | `/api/bookings/{id}/checkout` | Resume checkout for a pending booking |
| POST | `/api/payments/verify` | Verify payment using a booking or PaymentIntent ID |
| GET | `/api/bookings/{id}` | View your booking details |
| GET | `/api/bookings/{id}/confirmation-status` | Check payment and ticket confirmation progress |
| GET | `/api/bookings/user/history` | List your bookings |
| GET | `/api/bookings/user/history/page` | View your booking history with pagination |
| PATCH | `/api/bookings/{id}/cancel` | Cancel a pending booking |

See the [API examples](docs/api-examples.md) for request payloads, search parameters, and response shapes. See [Security](#7-security) for gateway access rules.

---

## 9. Running the Project

### 9.1. Prerequisites
- JDK 25
- Maven 3.9+
- Docker + Docker Compose
- Git
- Node.js 22.12+ and npm (for the frontend)

### 9.2. Quick Start (Docker Compose)

Run these commands from Bash (for example, Git Bash on Windows). The Compose stack starts the backend; start the frontend separately as described below.

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

To use published images instead, skip step 3, set `AIRLINE_IMAGE_TAG` to the intended published version, and run `docker compose --env-file docker-compose/.env -f docker-compose/docker-compose.yml pull` before step 4. The selected images must be available in the registry.

The API is available at `http://localhost:5000`.

### 9.3. Local Development (individual services)

Run the following commands in PowerShell from the repository root, after filling in `docker-compose/.env` as described in section 9.2.

```powershell
# Start the databases with their published localhost ports.
docker compose --env-file docker-compose/.env -f docker-compose/docker-compose.yml up -d userdb airlinedb locationdb flightdb seatdb pricingdb ancillarydb bookingdb paymentdb

# Use host-accessible Kafka, Redis, and MailHog for locally run services.
docker compose -f docker-compose/docker-compose.dev.yml up -d

# Install shared modules before running services individually.
mvn -f microservices/pom.xml clean install -DskipTests
```

Start Config Server and then Eureka, each in a separate PowerShell terminal opened at the repository root:

```powershell
# Terminal 1: Config Server uses the Git repository configured in its application.yaml.
mvn -f microservices/cloud/config-server/pom.xml spring-boot:run

# Terminal 2: wait for Config Server to start before running this.
mvn -f microservices/cloud/service-registry/pom.xml spring-boot:run
```

Config Server's default Git repository must be reachable. The main Compose file keeps Config Server and Eureka internal, so this workflow runs both on the host at ports `8888` and `8761`.

Run each business service in its own terminal. For example, start booking-service with the database credentials and published port from Compose:

```powershell
# Run from the repository root in a new terminal.
$env:SPRING_CONFIG_IMPORT = 'optional:configserver:http://localhost:8888/'
$env:EUREKA_CLIENT_SERVICEURL_DEFAULTZONE = 'http://localhost:8761/eureka/'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3309/airline_booking_db?useSSL=false&allowPublicKeyRetrieval=true'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = 'root'
$env:SPRING_KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:SPRING_DATA_REDIS_HOST = 'localhost'
$env:SPRING_DATA_REDIS_PORT = '6379'
mvn -f microservices/services/booking-service/pom.xml spring-boot:run
```

Repeat this pattern for the other business services, changing the POM path and datasource URL using this table. Set the variables in each terminal; environment variables are not shared between terminals.

| Service | Database container | Host MySQL port | Database |
|---|---|---|---|
| user-service | userdb | 3302 | airline_user |
| airline-service | airlinedb | 3303 | airline_db |
| flight-service | flightdb | 3304 | airline_flight_db |
| location-service | locationdb | 3305 | airline_location_db |
| pricing-service | pricingdb | 3307 | airline_pricing_db |
| ancillary-service | ancillarydb | 3308 | airline_ancillary_db |
| booking-service | bookingdb | 3309 | airline_booking_db |
| payment-service | paymentdb | 3310 | airline_payment_db |
| seat-service | seatdb | 3311 | airline_seat_db |

For payment-service, also set `STRIPE_API_KEY` to a Stripe test key and `STRIPE_CALLBACK_BASE_URL` to `http://localhost:5173` in its terminal. Notification-service does not need datasource settings; use `MAIL_HOST=localhost`, `MAIL_PORT=1025`, and `SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`. For the complete booking flow, run all business services and start the gateway in another terminal:

```powershell
mvn -f microservices/cloud/api-gateway/pom.xml spring-boot:run
```

The gateway listens on `http://localhost:5000`. Wait for the business services to register with Eureka before making requests.

### 9.4. Frontend

For either backend workflow, open another PowerShell terminal at the repository root:

```powershell
cd airline-system-frontend
npm install
$env:VITE_API_URL = 'http://localhost:5000'
npm run dev -- --port 5173 --strictPort
```

Open [http://localhost:5173](http://localhost:5173). Set `STRIPE_CALLBACK_BASE_URL=http://localhost:5173` for the backend so Checkout returns to this frontend. The frontend's example environment file points to a hosted API; the explicit `VITE_API_URL` above selects your local gateway.

### 9.5. Environment Variables

For Compose, put these values in `docker-compose/.env` and pass `--env-file docker-compose/.env`. For directly run Java services, set variables in the service's terminal using `$env:NAME = 'value'`.

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
STRIPE_API_KEY=sk_test_xxxxxxxxxxxxxxxxxxxxxxxx
STRIPE_CALLBACK_BASE_URL=http://localhost:5173

```

Compose sends email to MailHog by default; view captured messages at [http://localhost:8025](http://localhost:8025). When running `notification-service` directly, its default SMTP host is `localhost` on port `1025`. For an external SMTP server, override the mail settings above.

---

## 10. Tests

The recorded tests were run on a machine with a **2-core CPU and 8 GB of RAM**.

Start the backend and required infrastructure, then run smoke checks before cache or stress testing. Seed mock data for cache and stress tests; the VPS smoke suite creates its own fixtures. Run the commands below from the repository root in PowerShell unless noted otherwise.

### 10.1 Populate Mock Data Before Testing

The [future-flight seed](docs/seed/seed_future.py) populates synthetic airports, airlines, routes, flight instances, seats, and accounts. The recorded deployment contains **17 cities/airports, nine airlines, 272 directed routes, 21,216 flight instances, 2,545,920 seats, and 50,000 travelers**, plus nine airline owners and one administrator. Departures cover October 15 through December 31, 2026.

Run on the Docker database host with Python 3.9+ and `bcrypt`, after copying the script to `/root/seed_future.py`:

```bash
# Preview, then apply or resume the same run.
python3 /root/seed_future.py
python3 -u /root/seed_future.py --apply --directory /root/future-seed-20261015

# Verify persisted data and run gateway smoke checks.
python3 /root/seed_future.py --verify --directory /root/future-seed-20261015
python3 /root/seed_future.py --smoke --directory /root/future-seed-20261015
```
See the [seed guide and recorded verification](docs/seed/seed_future.md) for prerequisites, recovery, and date-sensitive smoke checks.

### 10.2 Smoke Test

Runs sequential checks through the gateway: authentication, catalog APIs, authorization, booking, Stripe payment verification, tickets, seat status, history, and MailHog confirmation email. Requires an existing administrator account; booking/payment mutations require a verified Stripe test key and MailHog configuration.

Copy [the harness](docs/test/smoke/vps_smoke.py) to `/root/airline-smoke/vps_smoke.py`, then run on the VPS with a private administrator credential file containing `email` and `password`:

```bash
export ADMIN_CREDENTIAL_FILE=/root/private/smoke-admin.json
python3 /root/airline-smoke/vps_smoke.py prepare --directory /root/airline-smoke/run-new
python3 /root/airline-smoke/vps_smoke.py supplemental --directory /root/airline-smoke/run-new
```

Complete the saved Stripe test Checkout URL manually, then verify payment and clean up the same run:

```bash
python3 /root/airline-smoke/vps_smoke.py verify-payment --directory /root/airline-smoke/run-new
python3 /root/airline-smoke/vps_smoke.py cleanup --directory /root/airline-smoke/run-new
```

Defaults: gateway `http://127.0.0.1:5000`, MailHog `http://127.0.0.1:8025`.

See the [smoke suite guide](docs/test/smoke/README.md) for schedule-fixture recovery, cleanup details, and separate browser coverage.

### 10.3 Cache Test

Measures flight-instance detail reads at **100 RPS for 60 seconds per pass**: 6,000 distinct IDs with the selected cache entries cold, then the same IDs with the cache warm. Checks response IDs, identical cold/warm payloads, and cache population. Requires Python, SSH access for seeded login and cache control, and the private seed credentials on the server.

```powershell
python docs/test/cache-latency/benchmark.py `
  --base-url http://201.18.216.184:5000 `
  --ssh-host root@201.18.216.184 `
  --credentials /root/future-seed-20261015/credentials.json `
  --count 6000 --rps 100 |
  Out-File -Encoding utf8 docs/test/cache-latency/results-laptop-new.json
```

[Recorded results](docs/test/cache-latency/REPORT-LAPTOP-100RPS-60S.md) and [raw measurements](docs/test/cache-latency/results-laptop-100rps-60s-2026-10-15.json):

| Metric | Cold | Warm | Latency reduction |
|---|---:|---:|---:|
| Mean | 79.92 ms | 52.16 ms | 34.73% |
| p50 | 61.00 ms | 49.40 ms | 19.01% |
| p95 | 179.71 ms | 61.65 ms | 65.70% |
| p99 | 281.62 ms | 97.42 ms | 65.41% |

All **12,000 requests** returned HTTP 200 with the requested instance ID; all **6,000 cold/warm pairs** had identical canonical JSON hashes. Percentages use `(cold − warm) / cold × 100` from unrounded measurements. See the [benchmark guide](docs/test/cache-latency/README.md) for artifacts and reproduction details.

### 10.4 Stress Test

Exercises booking creation and simulated payment verification with k6 at **10–160 virtual users**, holding each level for **120 seconds**. Requires Python 3.8+, working SSH/SCP access, k6 1.x, seeded traveler accounts and eligible seats, Kafka, notification-service, and MailHog.

```powershell
python -B docs/test/booking-load/run_k6.py --vus 10 20 40 80 160
```

[Recorded results](docs/test/booking-load/results/REPORT.md):

| Virtual users | HTTP requests/s | Payments/s | p50 | p95 | p99 | Endpoint failures |
|---|---:|---:|---:|---:|---:|---:|
| 10 | 22.11 | 11.05 | 407 ms | 911 ms | 1,186 ms | 0 |
| 20 | 38.94 | 19.46 | 457 ms | 1,136 ms | 1,433 ms | 0 |
| 40 | 50.59 | 25.29 | 475 ms | 1,933 ms | 2,464 ms | 0 |
| 80 | 51.93 | 25.97 | 784 ms | 3,525 ms | 4,274 ms | 0 |
| 160 | 52.94 | 26.46 | 2,294 ms | 6,496 ms | 6,942 ms | 0 |

All **13,158 measured bookings and payments** succeeded. Final reconciliation found exactly one confirmation email per measured booking, with **zero missing and zero duplicates**.
See the [stress test guide](docs/test/booking-load/README.md) for server settings, simulator setup, and run artifacts. The harness creates fresh result/private directories; test bookings and email remain on the server.

### 10.5 Seat Inventory Concurrency Test

Verifies pessimistic seat locking, atomic cabin capacity updates, and cancellation markers using real JPA repositories and service transactions. Covers competing reservations, concurrent cancellations of distinct bookings, duplicate reservation retries, repeated confirmation/cancellation, late messages after cancellation, and transactional rollback.

The VPS run using the deployed seat-service code and **MySQL 8.0.46** passed **all 9 integration tests** with **0 oversold seats across 100 competing reservation attempts per scenario**:

| Scenario | Competing attempts | Committed reservations | Expected rejections | Oversold seats |
|---|---:|---:|---:|---:|
| One seat, distinct bookings | 100 | 1 | 99 unavailable | 0 |
| Distinct seats, cabin capacity 5 | 100 | 5 | 95 capacity reached | 0 |

Run locally with JDK 25 and Maven 3.9+; this command uses an isolated H2 database and 100 attempts:

```powershell
mvn -f microservices/pom.xml -pl services/seat-service -am -Pseat-inventory-tests test "-Dtest=SeatInventoryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dinventory.attempts=100"
```

See the [suite and VPS reproduction guide](docs/test/seat-inventory/README.md), [VPS report](docs/test/seat-inventory/REPORT-VPS-20261026.md), and [raw results](docs/test/seat-inventory/results/vps-20261026-n100.json).

### 10.6 Booking and Payment Recovery Test

Verifies durable checkout progress, retries of unfinished reservations and payments, payment idempotency, and payment status reconciliation before releasing seats from expired bookings.

The VPS run using the deployed booking/payment service code and **MySQL 8.0.46** achieved **100% recovery across 100 simulated failures**. All **174 test executions passed** (118 booking, 56 payment), including the 100 fault trials and 74 additional correctness checks.

| Simulated failure | Recovered / attempted |
|---|---:|
| Seat reservation response lost | 20 / 20 |
| Reservation worker abandoned its lease | 20 / 20 |
| Payment initiation response lost | 20 / 20 |
| Payment provider unavailable | 20 / 20 |
| Checkout response database write fails | 20 / 20 |
| **Total** | **100 / 100 (100%)** |

Run locally with JDK 25 and Maven 3.9+; this command uses isolated H2 fixtures:

```powershell
mvn -f microservices/pom.xml -pl services/booking-service,services/payment-service -am test "-Dtest=BookingReservationRecoveryTest,BookingExpiryJobTest,BookingExpiryServiceTest,PaymentInitiationIntegrationTest,PaymentExpiryReconciliationTest,PaymentExpiryConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Tests use real transactional services and JPA repositories with mocked remote clients and payment providers. Failures are injected exceptions or seeded abandoned leases, with retry deadlines advanced in fixtures.

See the [suite and VPS reproduction guide](docs/test/checkout-recovery/README.md), [VPS report](docs/test/checkout-recovery/REPORT-VPS-20261026.md), and [raw results](docs/test/checkout-recovery/results/vps-20261026-n100.json).

### 10.7 Unit Test

Run with JDK 25 and Maven 3.9+:

```powershell
mvn -f microservices/pom.xml test
```

---

## 11. Configuration

All service configuration is managed by the **Config Server** which pulls from:

```
<https://github.com/hduong02/airline-system-config.git>
```

Each service bootstraps with:

```yaml
spring:
  config:
    import: optional:configserver:<http://localhost:8888>
```

`optional:` means if the Config Server is unavailable, the service falls back to its local `application.yaml`.

---

## 12. Known Limitations

| Limitation | Impact | Current mitigation / planned improvement |
|---|---|---|
| Cross-service updates are eventually consistent | Booking, payment, and seat state can temporarily diverge; partial failures require retries or compensation, which can also be delayed or fail | **Current:** Kafka choreography, durable checkout retries, and payment reconciliation before expired bookings release seats. |
| Database updates and Kafka publication are not atomic | A database commit can succeed without the corresponding event reaching Kafka, or an event can be published before a transaction rolls back; downstream booking, seat, or notification state can diverge | **Current:** Direct asynchronous Kafka sends; booking cancellation events are sent after commit, but there is no durable outbox. **Proposed:** Transactional outbox with retryable publication and idempotent consumers |
| Search evaluates all DB candidates before pagination | Memory use and sorting cost grow with candidate count; alliance, cabin availability, and fare checks make external calls that add latency and depend on downstream services | **Current:** Narrow route/date filters; per-request caches reuse airline, cabin, and fare lookups, while seat availability is fetched per candidate |
| Config Server has a single instance in Docker Compose | Remote configuration is unavailable during an outage; services can start only if local YAML and environment variables supply sufficient configuration | **Current:** `optional:` config import and local YAML fallback. **Proposed:** Redundant Config Server instances for deployments that require remote configuration availability |
| Kafka has a single broker in Docker Compose | No broker replication or failover; a broker outage interrupts event processing | **Proposed:** Multiple brokers with appropriately configured topic replication factors and minimum in-sync replicas |
| Notification delivery has no explicit dead-letter recovery or durable delivery tracking | Email exceptions propagate to the Kafka listener container, but there is no application-level record of delivery progress or explicit dead-letter replay workflow; emails may remain undelivered or be duplicated on redelivery | **Current:** Listener failures propagate for container error handling. **Planned:** Kafka dead-letter queue (DLQ). **Proposed:** Durable delivery tracking, deduplication, and an operational replay workflow |

---

## 13. Project Structure

Key source, configuration, and documentation directories are shown below. Generated build output and installed dependencies are omitted.

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
│   ├── docker-compose.yml           # Main backend stack
│   ├── docker-compose.dev.yml       # Host-accessible development infrastructure
│   └── docker-compose.vps.yml       # VPS deployment overrides
├── airline-system-config/           # Central service YAML configuration
│   ├── application.yml             # Shared configuration
│   └── <service-name>.yml           # Per-service configuration
├── airline-system-frontend/         # React application built with Vite
│   ├── package.json                # Dependencies and npm scripts
│   ├── vite.config.js              # Vite configuration
│   ├── public/                     # Public static assets
│   └── src/
│       ├── main.jsx                # Application entry point
│       ├── App.jsx                 # Application shell and routing
│       ├── pages/
│       │   ├── auth/               # Authentication screens
│       │   ├── Landing/            # Landing page
│       │   ├── Onboarding/         # Airline onboarding
│       │   ├── traveler/           # Search, booking, payment, and trip screens
│       │   ├── airline/            # Airline management screens
│       │   └── super-admin/        # System administration screens
│       ├── components/             # Reusable UI components
│       ├── services/               # API clients and service calls
│       ├── Redux/                  # Redux state management
│       ├── contexts/               # React context providers
│       ├── hooks/                  # Reusable React hooks
│       ├── assets/                 # Bundled images and other assets
│       ├── styles/                 # Shared styles
│       └── utils/                  # Utility functions
├── docs/
│   ├── api-examples.md              # API request and response examples
│   ├── architecture/               # Architecture diagrams
│   ├── seed/                       # Synthetic data generation and verification
│   └── test/
│       ├── smoke/                  # Gateway and end-to-end smoke checks
│       ├── cache-latency/          # Cold/warm cache benchmarks
│       ├── booking-load/           # k6 booking and payment load tests
│       ├── seat-inventory/         # Seat concurrency tests and reports
│       └── checkout-recovery/      # Booking/payment recovery tests and reports
└── README.md
```

---

*Built with Spring Boot 4.1.0 · Spring Cloud 2025.1.2 · Apache Kafka 4.3.1 · MySQL 8.0*
