# Trade API Service

Sprint 6 deliverable: the platform's HTTP entry point. Accepts an order,
authenticates the caller, applies Sprint 5's domain rules, and persists
the result against Sprint 3's schema. Business rules live in
`com.trading.domain` (copied as source, unmodified from `domain-engine/`);
this service owns transport and persistence only.

Sprint 7 extends this same application — not a second service — to also
host the Trade Executor and the market-data poller. See `## Sprint 7`
below for what that actually changed.

## Stack

- Java 21, Spring Boot 3.5.x
- MyBatis (parameterised SQL, XML mappers)
- Spring Security (OAuth2 Resource Server, JWT/HS256)
- Spring Kafka (producer for `orders`, consumer group `trade-executor` on
  `orders`, producers for `trade-events`, `market-data`, and each topic's
  `.DLT`)
- PostgreSQL

## Running locally

```bash
cd services/order-service
mvn clean verify
```

Requires a live PostgreSQL instance with Sprint 3's schema, seed data, and
all migrations through `008` applied (`databases/postgresql/apply.sh`), a reachable
Kafka broker with the six contracted topics created
(`infrastructure/kafka/scripts/create-topics.sh`), and these environment variables:

```
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=<your database>            # must match what apply.sh migrated
POSTGRES_USER=<your user>
POSTGRES_PASSWORD=<your password>
JWT_SECRET=<shared secret, must match the auth fixture's>
KAFKA_BOOTSTRAP_SERVERS=localhost:9092  # or wherever the broker is reachable
FAUXNANCE_API_KEY=<real key>            # read via System.getenv only,
                                         # never written to any properties/YAML file
FAUXNANCE_BASE_URL=<Fauxnance base URL>
POLL_INTERVAL_SECONDS=60                # floor-enforced in code against the 2000/day quota
```

```bash
mvn spring-boot:run
```

**Known gap:** `mvn clean verify` does not yet succeed on a machine with no
Postgres running — `AccountReadFlowIntegrationTest` needs a real database
connection. Testcontainers would remove this dependency; not yet added.

## Order lifecycle (Sprint 7 — asynchronous, via Kafka)

`POST /api/v1/orders` validates the request, writes the order at `NEW`,
commits, and returns `NEW` immediately — it does not price or fill the
order in the same request, because there is no price in the request and
pricing is the Executor's job. After the commit, the API publishes
`ORDER_PLACED` to `orders`, keyed by `accountId`.

The Executor — the separate `executor/` service, in its own consumer group
`trade-executor` — picks the message up independently, checks the
instrument, prices it against a live Fauxnance quote (`bid`/`ask`, never
`price`), and settles in one transaction: a guarded conditional update
(`WHERE status = 'NEW'`) as the first write, then the account's cash and
the position. It then publishes `ORDER_FILLED` or `ORDER_REJECTED` to
`trade-events`, keyed by the same `accountId`, and only then acknowledges
the Kafka offset.

The guarded update is what makes a duplicate delivery of the same message
harmless: a second delivery finds the order no longer `NEW`, updates zero
rows, and does nothing further — no second cash movement, no second event.

## Sprint 7 additions

### Kafka producer (order placement)
`KafkaOrderEventPublisher` — publishes `ORDER_PLACED` after the placing
transaction commits, keyed by `accountId`, with `acks=all`,
`enable.idempotence=true`, a high retry count, and at most 5 in-flight
requests per connection.

### Trade Executor (`com.trading.executor.*`)
- `consumer/OrderPlacedConsumer` — `@KafkaListener` in group
  `trade-executor`, manual acknowledgment (`ContainerProperties.AckMode.MANUAL`,
  configured in `KafkaConsumerConfig`), classifies failures via
  `DefaultFailureClassifier` into permanent-dead-letter-immediately versus
  transient-retry-with-backoff.
- `domain/FillRule` — pure function of an order and a `Quote`: a BUY fills
  at or above `ask`, a SELL fills at or below `bid`, rounded through
  `Money`; touches no database and no socket.
- `client/FauxnanceQuoteClient` — single-quote (`GET /quotes/{symbol}`) and
  batch (`GET /quotes?symbols=...`, up to 25) calls, both gated through the
  shared `FauxnanceQuotaTracker` so the fill path and the poller can never
  jointly exceed the 2000/day quota.
- `service/OrderSettlementService` — the guarded transaction described
  above; retries the optimistic-lock cash update a bounded number of times
  before surfacing `OrderSettlementRetryExhaustedException`.
- `service/KafkaOrderRepository` — despite the name, this is the
  `trade-events` publisher, not a database repository; the actual database
  writes live in `OrderSettlementService`/`OrderMapper`. A rename to
  something like `TradeEventPublisher` is a pending cleanup, not a
  functional gap.

### Market-data poller (`com.trading.executor.poller.MarketDataPoller`)
`@Scheduled` on `POLL_INTERVAL_SECONDS`, reads `WatchedSymbolsMapper`
(`SELECT DISTINCT symbol FROM positions WHERE quantity > 0`), batches the
result into groups of at most 25 for `FauxnanceQuoteClient.getBatchQuotes`,
and publishes **one Kafka message per symbol** to `market-data` — never one
message bundling multiple symbols, since that would destroy the per-symbol
ordering the topic contract promises. A `@PostConstruct` check refuses to
start if the configured interval would exceed the daily quota for a single
poller running alone.

## API

Implements `contracts/api-schemas/trade-api.yaml` — six operations under
`/api/v1/accounts` and `/api/v1/orders`. Every failure returns
`{"errorCode": "...", "message": "..."}`; see the contract for the full
error catalogue.

Every route requires `Authorization: Bearer <token>`. Signature, expiry
and issuer are verified before any controller runs
(`JwtValidators.createDefaultWithIssuer`). The expected issuer is
`app.security.jwt-issuer`, read from `JWT_ISSUER` and defaulting to
`auth-service`; it must equal the auth-service's `JWT_ISSUER`. A token
signed with the shared secret but carrying another issuer, or none, is
refused with `AUTH-401`, as `contracts/api-schemas/auth-api.yaml` requires. A token's
`accountId` claim is read directly (the contracted claim shape).
`DemoUserAccountMapping` remains only as a fallback for tokens without
that claim; the old `auth-stub` tokens (removed) carry no `iss`, so they are now
refused, and the demo must log in through `auth-service`.

## Testing

```bash
mvn clean verify
```

165+ tests: unit tests (Mockito, layered by service/controller), an
ArchUnit suite enforcing the controller/service/mapper boundary
(`LayeringRulesTest`), a bytecode purity check on the compiled domain
package (`DomainArtifactPurityTest`), a security slice
(`SecurityAndAccountAccessWebMvcTest`), one integration test against a
real Postgres connection (`AccountReadFlowIntegrationTest`), and the
Sprint 7 executor suite (`FillRuleTest`, `OrderSettlementServiceTest`,
`OrderExecutorIntegrationTest`, `OrderPlacedConsumerTest`,
`FauxnanceQuoteClientTest`, `FauxnanceQuotaTrackerTest`,
`MarketDataPollerTest`).

**Known gap:** `domain-engine/`'s own test suite does not yet include
`MoneyTest`, `OrderTest`, `PositionTest`, or `InstrumentTest` — these exist
only in this service's copy and should be pushed back, since Sprint 5 is
assessed independently.

The characterisation package at
`src/test/java/com/trading/api/characterization/` pins order-placement
behaviour from before the Sprint 7 change; the commit adding it is a
proper ancestor of the commit that changed `OrderServiceImpl` to return
`NEW` instead of filling synchronously, per the sprint contract's ordering
rule.

## Layout

```
src/main/java/com/trading/
    domain/       Sprint 5, copied as source -- no Spring/HTTP/SQL types
    api/
        controller/   HTTP only
        service/      domain rules, transactions, RejectedOrderRecorder,
                      KafkaOrderEventPublisher
        mapper/        parameterised SQL
        mapper/row/    MyBatis-safe intermediate types
        dto/          the wire contract
        exception/    the error catalogue
        config/       security, domain bean wiring, account mapping,
                      KafkaConsumerConfig, KafkaProducerConfig
    executor/         Sprint 7 -- Trade Executor and market-data poller
        consumer/     OrderPlacedConsumer, failure classification
        domain/       FillRule, Quote, ExecutionDecision
        client/       FauxnanceQuoteClient, FauxnanceQuotaTracker
        service/      OrderSettlementService, KafkaOrderRepository,
                      OrderExecutionService
        poller/       MarketDataPoller, WatchedSymbolsMapper
        event/        EventEnvelope (the shared 5-field envelope)
```

## Known open items

- `MoneyTypeHandlerTest` only exercises values that already carry two
  decimal places; a case with more precision would catch scale drift the
  current suite cannot.
- `service/KafkaOrderRepository` is named as if it were a database
  repository; it only publishes to Kafka. A rename to a name reflecting
  what it actually does (e.g. `TradeEventPublisher`) is pending, purely
  cosmetic, not a functional issue.
- `OrderSettlementService.settle(...)` fetches the Fauxnance quote inside
  the same `@Transactional` method as the guarded database writes, holding
  a Postgres connection open for the duration of the HTTP call (including
  its own internal retry-with-backoff). Moving the quote fetch outside the
  transaction, and passing the already-fetched `Quote` in, would reduce
  connection-hold time under load; not yet done.
- SEC2-673 (SonarQube gate on this service and on `etl-layer`) has not
  been run.