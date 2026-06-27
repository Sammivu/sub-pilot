# SubPilot Backend

Recurring billing infrastructure for African businesses — built on Nomba payment primitives.

---

## Tech Stack

- Java 21 (virtual threads via Project Loom)
- Spring Boot 3.3
- PostgreSQL 16
- Redis (distributed locks, rate limiting)
- Flyway (database migrations)
- ShedLock (prevents duplicate billing job runs)

---

## Quick Start (Local)

### Prerequisites
- Java 21
- Docker + Docker Compose
- Maven 3.9+

### 1. Clone and configure

```bash
git clone https://github.com/your-org/subpilot-backend.git
cd subpilot-backend
cp .env.example .env
# Edit .env — defaults work for local dev as-is
```

### 2. Start infrastructure

```bash
docker compose up -d
# Starts Postgres on :5432 and Redis on :6379
```

### 3. Run the application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

App starts on `http://localhost:8080/api`

Flyway migrations run automatically on startup.

---

## API Base URL

```
Local:  http://localhost:8080/api/v1
Prod:   https://subpilot-backend.railway.app/api/v1
```

---

## Authentication

**Dashboard (JWT):**
```
POST /api/v1/auth/signup
POST /api/v1/auth/login
→ Returns: { "token": "eyJ..." }
→ Use as: Authorization: Bearer <token>
```

**API Keys (downstream developers):**
```
POST /api/v1/settings/api-keys  (create)
→ Use as: Authorization: Bearer sk_live_...
```

---

## Nomba Mock Mode

By default, `NOMBA_MOCK_MODE=true` — all Nomba API calls are intercepted by `MockNombaGateway`.

- Charges always succeed by default
- Set `chargeSuccessRate` to 0.0 to simulate failures for dunning demo
- Flip `NOMBA_MOCK_MODE=false` once real credentials arrive — zero code changes needed

---

## Module Structure

```
co.subpilot/
├── auth/           JWT auth, API key auth, security config
├── merchant/       Merchant entity + onboarding
├── plan/           Plan CRUD + state transitions
├── customer/       Customer entity
├── subscription/   State machine (6 states, 11 transitions)
├── invoice/        Invoice lifecycle + sequential numbering
├── payment/        PaymentAttempt + idempotency
├── billing/        BillingEngineJob (@Scheduled every 5 min)
├── dunning/        Campaign, scheduler, self-cure
├── proration/      Upgrade/downgrade mid-cycle calculation
├── webhook/        Outbound delivery engine + inbound Nomba handler
├── nomba/          NombaPaymentGateway interface + implementations
├── event/          Append-only event log
├── analytics/      MRR, churn, payment success rate queries
├── refund/         Refund engine (Nomba Transfers API)
└── common/         BaseEntity, TenantContext, exceptions, config
```

---

## Deployment (Railway)

1. Connect Railway to this GitHub repo
2. Set all environment variables from `.env.example`
3. Railway detects the `Dockerfile` and builds automatically
4. Set `NOMBA_MOCK_MODE=false` and add real Nomba credentials once available

---

## Frontend

The React frontend lives at: https://github.com/AgiriTaofeek/sub-pilot

Expected on Netlify at: https://sub-pilot.netlify.app

or

Expected on Netlify at: https://subpilot.netlify.app

Set `FRONTEND_BASE_URL` to the Netlify URL in Railway environment variables.
