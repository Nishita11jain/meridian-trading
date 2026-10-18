# Chennai Capstone — SE2 Team 5

Enterprise Trading Platform, built across sprints by ET LEAP India, SE2-team5.

## Repository layout

```
databases/postgresql/   Sprint 3 — PostgreSQL schema, migrations, seed data, design docs
etl-layer/               Sprint 4 — Fauxnance ETL pipeline, DuckDB analytics, dashboard
                         Sprint 7 — FACT_TRADES incremental load, in src/fact_trades/
domain-engine/           Sprint 5 — framework-independent Java trading domain
services/order-service/  Sprint 6 — Spring Boot REST API, hosts Sprint 5 as source
services/executor-service/ Sprint 7 — the Trade Executor and market-data poller, their
                         own Maven project and service (split out on 27 Sep)
services/auth-service/   Sprint 8 — the real token issuer (NestJS)
frontend/                Sprint 9 — the Angular trading UI
contracts/               Shared contracts (api-schemas/, event-schemas/, analytics-schemas/)
config/                  application.yml, the shared ports and connection settings
infrastructure/kafka/    Sprint 7 — create-topics.sh, creates the broker's topics
design/                  Sprint 7 — Kafka topic design and reasoning (kafka.md)
DecisionLog.md           Key technical and workflow decisions, updated per sprint
```

**Folder structure:** the layout follows the structure given in the ETP System Architecture document (contracts,
databases, ETL layer, frontend, infrastructure, services, config), so we had to keep to it. For that reason the old
`auth-stub` test fixture and the stray `executor/` folder were removed; `auth-service` is the only token issuer.

Each folder has its own `README.md`, and `databases/postgresql/` and `services/order-service/`
also have a `DESIGN.md`/`design/` folder with sprint-specific detail. Start
there for anything beyond this overview.



## Building everything

```bash
# Database
cd databases/postgresql
./apply.sh

# Analytics ETL
cd etl-layer
pip install -e .
pytest
trading-analytics INFY.NS 1y

# Domain engine
cd domain-engine
mvn clean verify

# Order service (Trade API)
cd services/order-service
mvn clean verify
mvn spring-boot:run

# Auth service
# See RUNNING.md for detailed startup instructions.

cd services/auth-service
npm install
npm run dev

# Kafka (Sprint 7) — creates the three topics + their .DLT topics on the broker
bash infrastructure/kafka/scripts/create-topics.sh

# FACT_TRADES incremental load (Sprint 7) — see etl-layer/README.md for
# the three-command breakdown; this runs all three in one call
cd etl-layer
python -m fact_trades.src.pipeline run --database-path data/analytics.duckdb
```

## Documentation & Reviews

**Before running or demonstrating the platform, read:**

- **[RUNNING.md](RUNNING.md)** — How to start all services (Windows PowerShell instructions)
- **[OWASP_SECURITY_REVIEW.md](OWASP_SECURITY_REVIEW.md)** — Full OWASP Top 10 review across all modules and services
- **[SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md)** — Pre-demo security readiness (what must be fixed, what's ready)
- **[BACKLOG.md](BACKLOG.md)** — Sprint 10 backlog with ownership, status, and acceptance criteria
- **[DecisionLog.md](DecisionLog.md)** — Consolidated technical and workflow decisions

**Architecture & Design:**

- **[design/kafka.md](design/kafka.md)** — Kafka topics, consumer groups, and message flows
- `decision-log/` — Individual ADRs (architecture decision records) for each major choice

**Service-Specific:**

- `services/order-service/README.md` — Trade API, domain rules, order lifecycle
- `services/executor-service/README.md` — Trade execution, market-data polling
- `services/auth-service/README.md` — Authentication, token lifecycle
- `frontend/README.md` — Angular UI, routing, testing
- `databases/postgresql/README.md` — Schema, migrations, seed data

---

## Sprint status

| Sprint | Folder | Status |
|---|---|---|
| 3 — Trade Database | `databases/postgresql/` | Complete; seed applied via real `INSERT` statements (`seed-data.sql`), migrations run by `apply.sh` looping `migrations/*.sql` in order |
| 4 — Analytics ETL | `etl-layer/` | Complete; loads a DuckDB analytics database matching `contracts/analytics-schemas/analytics-schema.sql`, `pytest` green |
| 5 — Domain Engine | `domain-engine/` | Complete; framework-independent, enforced by Maven's banned-dependency rule; test suite includes `MoneyTest`/`OrderTest`/`PositionTest`/`InstrumentTest` |
| 6 — Trade API Service | `services/order-service/` | Complete; six endpoints, JWT verification with issuer validation, optimistic locking, orders reach a real terminal status (`FILLED`/`REJECTED`) |
| 7 — Event Backbone | `services/order-service/`, `etl-layer/`, `infrastructure/kafka/`, `design/` | Kafka topics, Trade Executor, market-data poller, and FACT_TRADES incremental load complete and verified live against a real broker/database; SonarQube gate (SEC2-673) not yet started |

## Known open items

- `services/order-service`: `mvn clean verify` still requires a live Postgres
  connection — `AccountReadFlowIntegrationTest` needs one. Testcontainers
  would remove this dependency; not yet added.
- `services/order-service`: `MoneyTypeHandlerTest` does not yet include a case
  proving correct rounding when the underlying value carries more than
  two decimal places.
- SEC2-673 (SonarQube gate on the executor and the pipeline) not yet run.
- **Currency conversion (decision log 0020, decision-log/0025):** INR holds all money. A dollar order locks the day's
  stored USD/INR rate (plus or minus a 0.5% margin) when it is placed; cash is reserved and settled in rupees at that
  rate, and the trade keeps the dollar price and the rate. The seed data's dollar fills carry their rate too. Orders
  placed before this change have no rate and settle as before.
- **Statements (decision log 0021):** trades and cash for a date range, CSV download and print-to-PDF. Capital
  gains are not calculated; the statement lists fills and leaves the cost-basis rule to a later decision.
- **Trade history and the blotter filters** have no live-stack test yet; they are covered by unit and mocked tests.
- The **queued-order** path (decision log 0019, decision-log/0015) was checked live on 7 October: an order for a
  closed market is settled by the executor when its market opens and the customer is notified.

See each folder's own `DecisionLog.md` entries for the reasoning behind
specific design choices, and for a fuller account of what's been
deliberately deferred versus genuinely outstanding.
