# Decision Log

This document records the key technical, architectural, workflow, and project decisions made during the development of the project, along with the reasoning behind them. It will be updated throughout the sprints as new decisions are made.

Sprints 3, 4 and 5 of the `leap-capstone-india` brief are complete, delivered
as `databases/postgresql/`, `etl-layer/` and `domain-engine/` respectively. The brief
deliberately ships no schema, no project skeleton and no code stubs for any
of these three sprints, so every entry below is a decision or an assumption
our team had to make to turn the domain description into something runnable.

---

## Sprint 3 — Trade Database (`database/`)

### Two identifiers per account
`accounts` carries a surrogate `id BIGINT GENERATED ALWAYS AS IDENTITY` as the
internal key every foreign key uses, and a separate `account_id VARCHAR(32)
UNIQUE` as the customer-facing reference (`ETP-4101`, etc.) that support staff
and statements quote. **Decision:** never treat these as interchangeable —
`orders.account_id` and `positions.account_id` reference the surrogate `id`,
not the customer reference, matching the brief's warning that "those two are
not the same thing and are not interchangeable."

### Account state and concurrency
**Assumption:** `status` is a `VARCHAR(20)` constrained by a `CHECK` to
`ACTIVE` / `SUSPENDED` / `CLOSED` rather than a Postgres native `ENUM`, so a
new state never requires an `ALTER TYPE` and stays a plain migration. A
`version INT DEFAULT 0` column was added to `accounts` for optimistic
locking; the database only stores the version, the domain engine (Sprint 5)
reports the version it loaded and Sprint 6 is responsible for taking the
lock. `cash_balance` is never allowed negative (`CHECK (cash_balance >= 0)`),
enforced at the database boundary rather than trusted to application code.

### Money as exact decimal
**Decision:** all monetary columns (`cash_balance`, `price`, `average_cost`)
are `NUMERIC(18,2)`, never `FLOAT`/`DOUBLE`, so no operation on them can
drift. Two decimal places matches INR and USD-style currencies; 18 digits of
precision was chosen as comfortably larger than any realistic balance without
being unbounded.

### Idempotency enforced by constraint, not by a read
`orders.idempotency_key` has a `UNIQUE` constraint. **Decision:** duplicate
detection is a database-level guarantee (SQLSTATE `23505`) rather than a
"check then insert" in application code, because two concurrent requests can
both pass a read-then-write check. This is what lets Sprint 5's
`DuplicateOrderChecker` seam and Sprint 6's exception mapping treat a unique
violation as the source of truth.

### Orders use a UUID primary key
**Assumption:** `orders.id` is `UUID`, not an identity column, so a client or
the domain layer can generate the id before the row is persisted (needed for
idempotent retries and for constructing `Order` objects in the domain engine
independent of the database).

### Instruments are soft-delisted, never deleted
`instruments.tradable BOOLEAN DEFAULT TRUE` is a flag rather than a deletion.
**Decision:** a delisted instrument keeps its row so historic orders still
resolve their foreign key, and new orders against it are refused by the
domain/service layer checking `tradable`, not by the row disappearing.

### Positions are stored, not recomputed
`positions` has a composite primary key `(account_id, symbol)` — one row per
instrument per account — and stores `quantity`/`average_cost` directly rather
than being a view over filled orders. **Decision:** this is deliberate
denormalization, documented because the dashboard cannot afford to replay an
order history on every page load; the data remains reconcilable against
filled orders (rule 9 in Sprint 5) as a consistency check, not as the primary
read path. Quantities are constrained `>= 0` since short selling is out of
scope.

### Indexing: only three deliberate indexes, each tied to a named query
See [databases/postgresql/design/indexes.md](databases/postgresql/design/indexes.md) for full
rationale. Summary of the decisions:
- A **partial** index `ix_orders_account_open_created (account_id, created_on
  DESC) WHERE status = 'NEW'` for Query 1 (open orders), kept partial because
  open orders are a small, fast-changing subset of a write-heavy table.
- A **full** composite index `ix_orders_account_created (account_id,
  created_on DESC)` for Query 2 (last 50 orders in any state) — not
  considered redundant with the partial index above because Query 2 has no
  status filter.
- A single-column index `ix_orders_created_on (created_on)` (ascending, to
  match the ascending scan direction of Query 4) for the nightly analytics
  extract.
- **Assumption:** Query 3 (current holdings) and Query 5 (resolve account by
  reference) need no new index because the `positions` primary key and the
  `accounts.account_id` unique constraint already serve them — this was
  treated as a real finding to write down, not a gap.
- Query 6 (running totals/ranking per account) was deliberately left
  unindexed: it's answered with a window function/CTE over an
  already-account-scoped row set, and the brief states this query "earns no
  index."

### Migrations and the apply command
**Decision:** migrations are five numbered `.sql` files applied in filename
order (`001` instruments → `002` accounts → `003` orders → `004` positions →
`005` indexes), kept immutable after the design review. `apply.sh` is the one
command that takes an empty database to migrated-and-seeded, reading its
target database from `TARGET_DATABASE`/`POSTGRES_DB` per the brief.

### Reading ahead: `market_price`, `price_history`, `watchlists`,
`watchlist_items` and `price_alerts` created early
**Decision:** the brief for Sprint 10 (Watchlists and Price Alerts) was read
ahead of schedule, and the five tables it needs were designed and migrated
now (`009_create_market_data_and_watchlists.sql`) rather than left until that
sprint starts, since they're pure additions with no dependency on Sprint 10's
own code — nothing before Sprint 10 reads or writes them. `market_price`
holds exactly one row per symbol, upserted on every `QUOTE` event off
`market-data`, so "what is this trading at right now" is a single-row lookup
rather than a `MAX(quote_as_of)` scan; `price_history` is the same event
stream written again, append-only, one row per tick, with no downsampling at
write time. `watchlists` is its own table with an `id` rather than a 1:1 row
per account, so an account can keep more than one named list;
`uq_watchlists_account_name` stops two lists sharing a name on one account
but not the same symbol appearing on several of an account's lists.
`price_alerts` carries a `status` so a threshold crossed once does not refire
on every later tick that still crosses it — a consumer only ever acts on an
`ACTIVE` alert and flips it to `TRIGGERED` in the same transaction as the
notification, the same guarded-state-transition idempotency pattern the
Trade Executor already uses for orders (`WHERE status = 'NEW'`). None of
this schema is wired to a consumer yet; that, and the API/UI around it, is
Sprint 10's own work.

---

## Sprint 4 — Analytics ETL (`etl-layer/`)

### Symbol universe
**Decision:** the analysis is scoped to three symbols — `INFY.NS`,
`RELIANCE.NS` (NSE) and `TATASTEEL.BO` (BSE) — satisfying the "at least two
NSE/BSE instruments" criterion while staying small enough to sanity-check by
hand. `AAPL` exists only as a test fixture and is deliberately excluded from
the analysed universe. See [etl-layer/claims.md](etl-layer/claims.md).

### Package layout mirrors the extract/transform/load contract
**Decision:** `extract.py`, `transform.py`, `load.py` and `pipeline.py` are
separate modules per the brief's requirement that transform "opens no socket,
reads no environment variable and writes nowhere." CLI/argument parsing lives
only in `cli.py` so configuration never leaks into the transform. The console
script entry point (`trading-analytics`) is declared in `pyproject.toml`
rather than relying on a bare `__main__` block.

### Two parallel pipelines, one contract
**Assumption:** the brief flags that Sprint 7 repoints this same pipeline at
the platform's own trades and loads the star schema in
`contracts/analytics-schemas/analytics-schema.sql`. **Decision:** rather than deferring that
entirely, a second pipeline (`extract_seed.py` / `transform_seed.py` /
`load_seed.py`, wired by `pipeline_seed.py`) already reads the seeded
Postgres trade data (via `db.py`/`psycopg2`) and loads it into a DuckDB file
(`data/analytics.duckdb`) built from that schema via
`scripts/create_duckdb.py`. The Fauxnance-facing pipeline keeps writing its
own flat file (`data/cache/clean_data.csv`) for the claims/dashboard, so the
two data sources are not mixed in one loader.

### Raw-response caching, keyed by symbol and range
**Decision:** `cache.py` persists the raw Fauxnance JSON to
`{symbol}_{range}.json`, never the cleaned frame, so changing the transform
never costs a fresh API pull and the team's combined usage stays far under
the 2000-request daily quota.

### Error handling is four distinct cases, not one `try`
**Decision:** `errors.py` defines `RateLimitError`, `ClientHTTPError`,
`RetryableNetworkError` and `MalformedPayloadError` as a small hierarchy off
`AnalyticsError`. `client.py` maps HTTP 429 to `RateLimitError` (stop the
whole run), other 4xx to `ClientHTTPError` (skip that symbol, continue),
connection/timeout errors to a bounded retry-with-backoff
(`RetryableNetworkError` on exhaustion), and leaves payload-shape problems
(missing field, high < low, non-numeric price) to the transform layer, which
silently drops the offending row rather than raising — a row is only ever
skipped, never "fixed."

### Charts are hand-rolled inline SVG, not a charting library dependency
**Assumption:** although `plotly`/`matplotlib` were both viable per the
brief, `dashboard.py` renders line and bar charts as raw SVG strings embedded
directly in the HTML it writes. **Decision:** this guarantees the artefact
opens with zero network access and zero JavaScript, satisfying "the chart
artefacts have to open without a network" more strictly than a
library-generated file would, at the cost of hand-maintaining the plotting
code.

### `claims.md` and evidence
Three claims were chosen (normalized price performance, drawdown resilience,
sample comparability), each naming the chart/fragment that supports it and
each traced back to a numeric table of the underlying candles, per the "what
would have to be true for this claim to be wrong" expectation in the brief.


## Sprint 5 — Trading Domain Engine (`domain-engine/`)

### Base package name
**Decision:** the domain lives under `com.trading.domain`, chosen because
Sprint 6 absorbs this package under the same name as source, so the name says
domain rather than transport, per the brief's explicit instruction.

### Money is an immutable value type, not `double` or raw `BigDecimal`
**Decision:** `Money` wraps a `BigDecimal` scaled to 2 places with
`HALF_UP` rounding, is immutable, and every arithmetic operation
(`add`/`subtract`/`multiply`) returns a new instance. `equals`/`hashCode`
compare numeric value (`compareTo`), not scale, so `1.10` and `1.100` are
still the same amount. `Account.debit` refuses a debit that would leave the
balance negative *before* subtracting, matching the brief's "refused before
anything is subtracted."

### Two identifiers and a reported (not enforced) lock version on `Account`
Mirrors the database: `accountId` (numeric, used in the API/JWT/every order)
and `accountReference` (the string quoted on a support call) are distinct
fields. **Decision:** `Account.version` is stored and reported but the domain
never takes the lock itself — optimistic locking is Sprint 6's
responsibility against the database, the domain only carries the value it
was loaded at.

### Idempotency check is a seam, not a database call
**Decision:** `DuplicateOrderChecker` is a one-method interface
(`isDuplicate(String idempotencyKey)`) that `OrderPlacementService` depends
on. This lets rule 8 be evaluated and unit-tested with no database on the
classpath, while making it explicit that the *real* authority for
uniqueness in Sprint 6 is the `orders.idempotency_key` unique constraint from
Sprint 3, not this check — the check exists to fail fast, not to replace the
constraint.

### A seventh exception type beyond the six specified
**Decision:** the brief lists six required exception cases (`ACC-404`,
`ACC-403`, `INS-404`, `ORD-400`, `ORD-409` ×2) but explicitly leaves rules 4
and 5 (quantity/price out of range) as an open design question. Our answer:
`InvalidOrderParametersException` (`VAL-422`) was added, still extending the
shared `DomainException` base (which carries a `code()` and nothing
HTTP-specific), so a caller that never ran a validator — such as the Trade
Executor in Sprint 7 replaying an order — is still refused correctly rather
than throwing an unchecked exception or NPE.

### Enumerations and DTO validation kept literal
`AccountStatus`, `OrderSide`, `OrderStatus` reproduce the exact literals from
the brief (including the double-`L` `CANCELLED`), since they are shared with
the database `CHECK` constraints and, eventually, the generated Angular
types. `PlaceOrderRequest` is a Java `record` validated with
`jakarta.validation` annotations (`@Positive`, `@DecimalMin("0.01")`,
`@Digits(integer = 17, fraction = 2)`, etc.) rather than hand-written
checks, satisfying the brief's requirement that quantity/price rules are
enforced both as a DTO constraint and again inside the domain rules, so the
domain still holds for a caller that bypasses validation.

### Dependency constraint enforced by build, not convention
**Decision:** `jakarta.validation-api` is the only non-test compile
dependency; the brief's ban on Spring/servlet/JDBC/MyBatis/HikariCP reaching
the domain (even transitively) is treated as a build-time guarantee via the
enforcer plugin's `bannedDependencies` rule, so a violation fails `mvn test`
immediately instead of being caught later in review.

---

## Sprint 6 — Trade API Service (`services/order-service/`)

### Revised decision: the domain is consumed as copied source, not a published artifact

An earlier version of this log recorded `trade-api-service` depending on
`domain-engine` as a published Maven artifact
(`com.se2team5.trading:domain-engine:1.0.0`), resolved from the local
repository. **That decision has been superseded.** The Sprint 5 package is
now copied into this service under `com.trading.domain`, as source, keeping
the original package name. A fresh checkout of `trade-api-service` alone
builds with `mvn clean verify` and no prior step against another project —
there is no artifact and no `mvn install` dependency between the two. The
`domain-engine/` folder is retained unchanged as its own independently
buildable, independently assessed project; the copy inside
`trade-api-service` is the one the running platform actually uses from this
sprint forward. `manifest.env`'s `DOMAIN_ENGINE_GROUP_ID` /
`ARTIFACT_ID` / `VERSION` entries, written for the superseded decision, are a
known, not-yet-corrected staleness — see **Open items** below.

### Layering enforced by two automated checks, not by convention
Copying the domain in as source means it now compiles inside the same
module as the API layer, which makes the "no Spring/HTTP type in the
domain" boundary a matter of discipline unless it is checked. **Decision:**
two tests enforce it directly rather than relying on code review alone.
`LayeringRulesTest` uses ArchUnit against `com.trading.api` to assert
controllers import nothing from the mapper package, `java.sql`, or MyBatis,
and that the service layer takes no servlet type as a parameter.
`DomainArtifactPurityTest` scans the *compiled bytecode* under
`target/classes/com/trading/domain/` for the literal strings `spring`,
`mybatis`, and `servlet`, so a violation fails the build even if it would
have compiled without one.

### A DTO layer, deliberately separate from the domain objects
**Decision:** response shapes (`AccountResponse`, `BalanceResponse`,
`OrderResponse`, `OrderHistoryEntry`) are their own types in
`com.trading.api.dto`, not the domain objects serialized directly. Two
concrete reasons, not a general preference: `AccountResponse.accountId` is
contractually the string business reference
(`Account.getAccountReference()`), while every other use of "accountId" in
the platform — including `Account.getAccountId()` itself — means the
numeric key; serializing `Account` directly would put the numeric value
under a field name the contract defines as the opposite. Separately,
`AccountResponse` also carries `lastUpdated`, which is not a field on
`Account` at all and is read via a second mapper call
(`AccountMapper.findLastUpdated`) — no single domain object represents that
combined shape. `PlaceOrderRequest`, by contrast, is imported and used
directly from `com.trading.domain` with no service-layer equivalent,
because its shape already matches both the wire contract and the domain
rule with no adjustment needed.

### `Row` classes as a deliberate intermediate layer between MyBatis and the domain
**Decision, following a real defect:** `AccountMapper`/`OrderMapper`
originally mapped query results directly onto the domain objects via a
MyBatis `<constructor>` resultMap. This failed at runtime —
`NoSuchMethodException: Account.<init>(..., java.lang.Integer)` — because
MyBatis's reflection-based constructor lookup boxes a primitive (`version:
int`) to its wrapper type internally whenever a custom `typeHandler` (used
here for `Money`) is also present in the same `<constructor>` block,
regardless of the `javaType` declared in the XML. Java's
`getDeclaredConstructor()` requires an exact type match, so the lookup
fails. The fix: `AccountRow` and `OrderHistoryRow`
(`com.trading.api.mapper.row`) are plain, mutable classes populated via
ordinary property-based `<result>` mapping, which never goes through
constructor reflection. Conversion from a `Row` into the real domain
object happens afterward in plain Java
(`AccountServiceImpl.toDomain`/`OrderServiceImpl.toDomain`), where
primitive/boxed conversion is a normal, compiler-checked method call. This
defect was only caught by `AccountReadFlowIntegrationTest`, the one test in
this service that exercises real MyBatis reflection against a live
database; every unit test mocks the mapper interfaces and could not have
surfaced it.

### `ConcurrentModificationDetectedException`, and the code it is given
**Decision:** optimistic locking is required (`accounts.version`, taken as
part of the write per Sprint 3/5's design), but
`contracts/api-schemas/trade-api.yaml`'s `errorCode` enum has no entry for "another
request already modified this row, retry." A new exception,
`ConcurrentModificationDetectedException`, is raised when
`updateCashBalanceWithVersion` or a position write affects zero rows. It is
defined in `com.trading.api.exception`, not the domain package, since a
write-conflict is a persistence-layer concern rather than a Sprint 5
business rule, but it still extends the domain's `DomainException` base so
`GlobalExceptionHandler`'s existing single handler picks it up with no new
code. `ORD-409` was chosen as its code — the same family as the
duplicate-idempotency-key case — as the closest semantic fit available in
the given catalogue. This is flagged as a genuine gap in the contract
worth raising, not a confirmed-correct mapping.

### `cancelOrder`'s 404/409 resolution
**Decision, resolving a contradiction found in the given contract:**
`contracts/api-schemas/trade-api.yaml`'s `DELETE /orders/{id}` documents its
missing-order response as HTTP 404, but that same response's own example
payload carries `errorCode: ORD-409` — the two disagree, since the
catalogue itself maps `ORD-409` to status 409, not 404. Implementation
follows the concrete example over the declared status: a missing order and
an already-terminal order both raise `DuplicateOrderException("ORD-409",
...)`, at HTTP 409. This is treated as the more specific of two
contradictory signals in the contract, not as an assumption resolved with
confidence — it is a question for whoever owns the contract, and is logged
here so the choice is visible rather than silent.

### JWT verification via Spring Security's OAuth2 Resource Server
**Decision:** authentication is implemented with
`spring-boot-starter-oauth2-resource-server` and a `JwtDecoder` built from
the shared secret (`NimbusJwtDecoder.withSecretKey`, algorithm pinned
explicitly to `HS256`), rather than a hand-written servlet filter. Pinning
the algorithm rather than reading it from the token's own header is a
deliberate defence against a forged `"alg": "none"` header being trusted.
Every authentication failure — missing header, wrong scheme, expired
token, forged signature — is routed through one `authenticationEntryPoint`
that writes the identical `{"errorCode":"AUTH-401","message":"Unauthorised"}`
body regardless of which specific check failed, per the brief's explicit
instruction that a more specific message would tell a caller which of the
four failures they had produced. Session management is set `STATELESS`;
no server-side record of a caller is kept between requests.

### `DemoUserAccountMapping`: resolving account ownership without an `accountId` claim
**Decision, forced by a fixed external constraint:** the team's provided
authentication fixture (`auth-stub`, not modifiable) issues tokens signed
with only a `sub` (username) and `roles` claim — no account reference of
any kind, unlike the claim shape `contracts/api-schemas/auth-api.yaml` specifies for
the real Sprint 8 service. `ACC-403` still has to be enforced correctly:
a valid token belonging to one user must be refused when it addresses
another user's account. `DemoUserAccountMapping`
(`com.trading.api.config`) is a small, explicit `Map<String, Long>`
resolving a known username to its real account id, read from the token's
`sub` claim in every controller in place of the `accountId` claim the
fixture does not provide. This is documented as a deliberate, temporary
bridge, not a substitute for a real user/account relationship: that
relationship is Sprint 8's own concern, in its own database, kept separate
specifically so that replacing the fixture with the real auth service
later is a configuration change (per the brief: "only configuration
should change") rather than a change to this service's Java code.

### Multi-stage Dockerfile
**Decision:** the build stage (`maven:3.9-eclipse-temurin-21`) carries
Maven and a full JDK; the runtime stage (`eclipse-temurin:21-jre-alpine`)
carries neither, and runs the jar as a non-root user. `pom.xml` is copied
and its dependencies resolved in a layer before the source is copied, so
changing a single source file does not invalidate the cached dependency
layer on rebuild. The health check depends on
`spring-boot-starter-actuator`'s `/actuator/health` endpoint, added for
this purpose; without it, the container has no way to report readiness
beyond "the process has not crashed."

### `docker-compose.yml`: what is, and is not, containerized this sprint
**Decision:** the root compose file defines `postgres` and
`trade-api-service` only, on a shared `trading-net` network, with
`trade-api-service` waiting on Postgres's health condition rather than a
fixed startup delay. `auth-stub` is deliberately excluded: the brief scopes
the Dockerfile requirement to "this folder" (`trade-api-service`), the team
was instructed not to modify `auth-stub`'s files, and Sprint 6 does not
require it to be containerized. It continues to run separately via `npm
start` for this sprint. Kafka is excluded per the brief's own statement
that "nothing this sprint needs Kafka."

### Two configuration defects found and corrected during this sprint
Two issues were found by exercising the running service rather than by
reading the code, and are logged as findings, not just fixes. First,
`application.yml` had no `mybatis.mapper-locations` entry despite three
real XML mapper files existing under `src/main/resources/mapper/`; every
XML-backed query failed at runtime with `BindingException: Invalid bound
statement`, invisible until that specific method was actually called,
since MyBatis does not validate every interface method against a matching
statement at startup. Second, `application.yml` carried fallback default
values for `POSTGRES_USER`/`POSTGRES_PASSWORD`, committed in plain text;
these were removed so a missing credential fails startup loudly, matching
the same rule already applied to `JWT_SECRET`.

### Sprint 6 contract delivered: `contracts/api-schemas/trade-api.yaml`
The binding OpenAPI 3.1.0 specification for the Trade REST API was delivered
by the team as a complete contract including all six operations, error codes,
authentication scheme, and business rules. This contract has been added to
the repository at `contracts/api-schemas/trade-api.yaml`. All six endpoints match the
contract exactly: `POST /api/v1/orders`, `DELETE /api/v1/orders/{id}`,
`GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/balance`,
`GET /api/v1/accounts/{id}/positions`, and
`GET /api/v1/accounts/{id}/orders` with optional query filters.

### Swagger UI is exposed locally, not gated behind auth
**Decision:** Swagger is wired through `springdoc-openapi-starter-webmvc-ui`
and served from `/swagger-ui.html` and `/v3/api-docs`. Those doc endpoints
are explicitly permitted in `SecurityConfig` so the local UI can load and
exercise the API without a token just to render the contract. The API
itself remains bearer-protected; only the documentation surface is open.

### Account access follows contract precedence and hides the reason for 403
**Decision:** account lookups now check existence before token ownership in
order placement, so a missing account returns `ACC-404` before any access
check. For account-scoped reads, suspended/closed accounts and wrong-token
access both return the same `ACC-403` / `account is not active` response,
matching the contract's deliberate anti-enumeration rule.

### Order history exposes no executed price until a fill exists
**Decision:** order history queries map `executed_price` to `NULL` in the
read model until the Trade Executor exists and actually writes a fill price.
That keeps the API from implying a settled execution price where none has
been recorded yet.

### Sprint 6 decisions we made
- The domain was copied in as source so the service builds from a fresh
  checkout without a prior Maven install or a cross-project artifact.
- Swagger is open only on the docs surface, so the local UI can render the
  contract and exercise calls while the API routes themselves still require
  JWT.
- `ACC-403` is reused so the API does not reveal whether the caller hit a
  suspended account or the wrong account, which avoids key enumeration.
- `ACC-404` comes before the access check in order placement because the
  contract says account existence is rule 1 and token reach is rule 2.
- `executedPrice` is `NULL` until a fill exists because there is no Sprint 6
  executor yet, so the API should not invent a fill price.
- `DemoUserAccountMapping` exists because the auth fixture only gives a
  username claim, so it bridges that temporary token shape to real account ids
  until Sprint 8 replaces the fixture with the actual auth service.

### Integration tests: environment isolation for real Postgres connection
`AccountReadFlowIntegrationTest` requires a real Postgres database running
locally with the Sprint 3 schema and seed data already applied. Maven's test
runner does not automatically load the `.env` file, so the test environment
was isolated via a new `application-test.yml` profile, activated with
`@ActiveProfiles("test")`, that provides explicit POSTGRES_HOST, PORT, DB,
USER and PASSWORD values. This ensures the integration tests can find the
database without relying on environment-variable forwarding through Maven.
**Known issue:** if Postgres is not running or the credentials are incorrect,
the 3 integration tests will still fail with a 500 connection error; this is
a test infrastructure limitation, not an application defect. The remaining
100 unit tests (service, controller, mapper, domain, exception, and layering
tests) all pass consistently in any build environment with no external
dependencies.

### JWT_SECRET and database credentials: environment variable resolution
**Correction made during Sprint 6 close:** `application.yml`'s
`app.security.jwt-secret` was changed from a hardcoded value to
`${JWT_SECRET}` so it reads from the environment at runtime, matching the
pattern already applied to database credentials. This ensures both secrets
(Postgres password and JWT signing key) are loaded only from the environment,
never from checked-in defaults, failing startup loudly if either is missing.




---

## Sprint 7 — Event Backbone (`contracts/event-schemas/kafka-topics.md`)

### Topic names follow the binding contract, not the informal shorthand
**Decision:** the platform uses `orders`, `trade-events`, and `market-data`
as the three contracted topics, with matching `.DLT` topics created explicitly
for each one. The catalogue in `contracts/event-schemas/kafka-topics.md` is binding, and the
topic names are part of that contract; the first topic is called `orders`
here rather than `trades` because that is the accepted-order queue the rest of
the platform now publishes to and consumes from.

### One key per ordering domain
**Decision:** `orders` and `trade-events` are keyed by `accountId`, while
`market-data` is keyed by `symbol`. The reason is the same in both cases: the
key decides the partition and the partition decides the ordering guarantee.
Account-level ordering must be preserved for accepted orders and lifecycle
events, while symbol-level ordering must be preserved for quotes. Keying by an
order id would scatter one account's messages across partitions and destroy the
ordering guarantee that the executor and later consumers rely on.

### Partition counts are chosen for the workload, not for symmetry
**Decision:** `orders` and `trade-events` use three partitions, because that is
enough to let three executor instances share the work while still preserving
per-account ordering. `market-data` uses six partitions because it is the
higher-volume stream. Partitions can be increased later but never decreased,
and increasing them rehashes keys, so an account's history splits from that
point onward; the counts are deliberate, not placeholders.

### Dead-letter topics are explicit topics, not auto-created accidents
**Decision:** each of the three contracted topics has an explicit `.DLT` topic
created alongside it. Auto-creation stays off because the broker would create
one-partition topics with the wrong retention and nothing would tell us it had
happened. A poison message must fail loudly and land somewhere observable, not
silently create the wrong topic shape on first use.

### Consumers ignore unknown fields
**Decision:** every Kafka consumer in the platform must ignore fields it does
not recognise. The payload envelope is designed for additive evolution, and a
consumer that fails on an unknown field would turn an additive change into an
outage. This keeps the message contract forward-compatible as the event shapes
grow.

### Topic creation is a checked-in command, not a one-off shell history entry
**Decision:** the broker setup lives in `infrastructure/kafka/scripts/create-topics.sh`, and
`design/kafka.md` names that script as the supported creation command. The
topics need to be reproducible from a clean broker, so the command must be
stable, documented, and rerunnable by anyone on the team.

### The Trade Executor is merged into `trade-api-service`, not a second Maven project
**Decision:** the executor's consumer, settlement service, and poller live in
`com.trading.executor.*` inside the existing `trade-api-service` application,
sharing one `pom.xml`, one JVM, and one Docker deployment, rather than the
two-project/two-container shape the sprint contract originally describes.
This was a deliberate instructor direction to reduce infrastructure given the
team's constraints, not a shortcut taken unprompted. The consumer group name
(`trade-executor`), the topic contract, and the guarded-transaction settlement
logic are unaffected — Kafka does not know or care whether producer and
consumer share a JVM, and the duplicate-delivery guarantee holds identically
either way.

### FACT_TRADES merges on `source_order_id`, and never fabricates a missing fact
**Decision:** the incremental load (`etl-layer/src/fact_trades/`) upserts
into `fact_trades` keyed on `source_order_id`, matching the contract's unique
constraint, so a re-run never double-counts. Every order status is loaded,
not just fills, because fill rate needs the full denominator. `trade_value`
is always recomputed from `quantity × executed_price` for a fill, or
`quantity × the requested price` otherwise — never trusted from an upstream
value — and a row that fails a referential-integrity, sign, or status check
is dead-lettered with the specific reason and batch id, never silently
dropped and never forced through with a placeholder dimension row.

### `dim_account` is loaded insert-only, not full SCD2
**Decision:** `dim_account` carries SCD-Type-2 columns (`effective_date`,
`end_date`, `is_current`) in the contract, but this sprint's scope is
explicitly one incremental load with no history rebuild. The pipeline inserts
an account as a new current row the first time it's seen and does not detect
or close out changed attributes on existing accounts. This is a stated,
deliberate gap, not an oversight, and full SCD2 change-tracking is left for a
later sprint if the platform needs to answer "what did this account's status
used to be."

## Sprint 8 — Auth Service (`services/auth-service/`)

### `auth-service` runs its own migrations on boot, not through `databases/postgresql/apply.sh`
**Decision:** unlike every other table in the project, the `auth` schema is not
created by `databases/postgresql/apply.sh`. `auth-service` applies its own numbered
migrations (`src/database/migrations/`) from `run-migrations.ts` each time it
starts. The underlying reason is the schema isolation stated in the header of
`001_init_auth_schema.sql`: the credential store deliberately lives in its own
`auth` schema, not the trading schema's `public`, because the architecture
diagram is explicit that "no trading table is touched from here". `account_id`
is a plain `BIGINT` with no foreign key to `accounts.id`, so the auth schema
never references the trading schema even at the constraint level, and account
existence is checked at the application layer through the Trade REST API. A
schema that shares nothing with the trading tables is one the service can
create and evolve on its own, so it does. The migrations are idempotent
(`CREATE ... IF NOT EXISTS`) and safe to run on every boot. Setting
`AUTH_RUN_MIGRATIONS=false` turns this off for a deployment that would rather
manage the schema separately.

### SEC2-680: Refresh tokens use fixed 7-day hard expiry from login, not sliding window
**Problem:** Refresh tokens previously updated `expires_at = now() + 7 days` on every refresh, creating a sliding window that allowed indefinite session extension if the user kept refreshing.

**Decision:** Implement an immutable hard expiry that cannot be extended:
- `created_at` (IMMUTABLE): Timestamp of the original login, set once and never changed
- `hard_expiry` (IMMUTABLE): created_at + 7 days, set once and never changed  
- `updated_at` (MUTABLE): Tracks refresh activity for audit purposes, auto-updated on every change via trigger

**Implementation:** The `refresh_tokens` table now stores three timestamps (migration `003_update_refresh_tokens_fixed_expiry.sql`). On every refresh via `/auth/refresh`, only the token hash is updated using `updateTokenHashOnly()`; both immutable columns remain unchanged. After 7 days from login, all refresh attempts fail with "Session hard expired, please login again", forcing re-authentication regardless of activity level.

**Enforcement:** Hard expiry is enforced at two layers:
1. **Database layer**: `CHECK (hard_expiry > created_at)` constraint and immutable column design
2. **Application layer**: `RefreshTokenService.rotate()` and `rotateWithUserLookup()` explicitly check `isHardExpired()` before issuing a new token

**Result:** Maximum session lifetime is exactly 7 days from login. This closes the security gap in SEC2-680 and provides a bounded window for compromised refresh tokens to be exploited.

---

## Sprint 9 — Extensions (market hours, portfolio, watchlists, notifications, preferences)

Entries in this section are numbered, unlike those above, because the code cites them by number. The Sprint 10
extension decisions are one file each in `decision-log/` (0001 to 0016); the entries here carry on from 0017 so
that no number is used twice. The seams between the Sprint 10 modules are decision-log/0001, not repeated here.

### 0017 — Market hours: each order waits for its own market
**Status:** the placement rule below is superseded in part by 0019. Queued orders replace the refusal at
placement; the hours, the holiday calendar and the per-market `tradable` status still hold.

**Problem:** the instruments trade on markets that close, and nothing in Sprints 6 to 8 modelled trading hours.
An order placed out of hours could be accepted and then priced against a stale quote.

**Decision:** the hours are enforced in both services, with the same rule and the same tests.
- The Trade REST API reports each instrument's `tradingStatus` (OPEN or CLOSED) and `nextOpen`, computed per
  request from the trading-hours rule and the `market_holidays` table.
- The executor's `MarketHours` decides when a queued order may settle. A closed market holds the order.
- The hours: NSE and BSE (`.NS`, `.BO`) Monday to Friday 09:15 to 15:30 India time; US (a plain ticker) Monday
  to Friday 09:30 to 16:00 New York time; FX (`FX:`) Sunday 17:00 to Friday 17:00 New York time; crypto (`X:`)
  always open.

**Why two copies:** `order-service` and `executor-service` share no Maven artifact, by the same decision that
copies the domain engine in as source. The rule lives in both, with both test suites.

**Not modelled:** early closes, special sessions, and US pre-market and after-hours trading.

### 0018 — `portfolio-api.yaml` diverged from the published contract; reconciled on 8 October
**Status: done.** `contracts/api-schemas/portfolio-api.yaml` is now the published 3.1.0 contract with one added route
(`/history`, for the value chart) and a short note on how we build it. The summary has a `baseCurrency` and totals in
rupees, `/pnl` returns the published object (with `from`, `to` and `bySymbol`), and the health check is `GET /health`.
`portfolio-client`, the portfolio and dashboard screens and the mocked journeys were moved to that shape. The
conversion to rupees is decision-log/0025; the one place we still differ from the brief is where prices come from
(decision-log/0006).

### 0019 — Orders for a closed market are queued, not refused
**Problem:** an order placed while its market was closed was refused with MKT-409. A user who orders outside
hours had to come back later, and an order placed at 15:29:59 and settled at 15:30:01 was refused at settlement
rather than placed.

**Decision:** a closed market accepts the order and queues it. It is stored as `NEW`, and it settles when its
own market opens.

- **Each order waits for its own market.** NSE and BSE orders wait for the NSE open, US orders for the US open,
  FX for the FX open. Crypto is never closed, so it is never queued.
- **Cash and shares are reserved from the moment of placement.** A queued buy reserves its quantity times its
  limit price. A queued sell reserves its quantity of shares. The reserve is the sum of the account's NEW orders,
  so it is released automatically when an order fills, is rejected, or is cancelled. No separate reservation
  table is kept. Placement locks the account row, so two orders placed at the same moment see each other.
- **Release happens on a 30-second poll in the executor.** The poll finds the oldest NEW orders and settles each
  one whose market is open at that moment, through the same settlement transaction and status guard as a live
  order. An order therefore settles at most about 30 seconds after its market opens. The database is the queue,
  so a restart loses nothing.
- **At the open, the order is checked again** against the cash or shares it reserved, and its limit price is
  checked against the quote, as before. Settlement with a limit price the quote does not meet is rejected with
  PRICE_NOT_MET.
- **No expiry.** A queued order stays NEW until it fills, is rejected, or is cancelled.
- **Cancel is unchanged.** A NEW order can be cancelled at any time before it settles.
- **The publish reconciler skips queued orders.** It replays NEW orders whose event may have been lost, but an
  order waiting for its market is not lost, so replaying it would only re-send the same event.

**Consequences:** MKT-409 is no longer returned on placement, and the executor no longer rejects an order for a
closed market (`MARKET_CLOSED` remains in the vocabulary for rows written before this decision). The trade API
contract's placement description records queued orders.

**Not decided here:** an expiry period for queued orders (brokers often use 90 days), and whether to publish a
"queued" notification when the order is placed. Both can be added without changing this design.

### 0020 — One home currency (INR); USD instruments are converted at a locked rate
**Status: built on 8 October.** A dollar order locks the stored USD/INR rate (plus the margin for a buy, minus it for a sell) in `orders.fx_rate`; placement checks and reserves cash in rupees, the executor settles in rupees at that rate, and `trades` keeps the dollar price and the rate. Positions stay in the instrument's own currency. The rate comes from `fx_rates`, filled once a day by the executor (decision-log/0025). Orders placed before the change have no rate and read as rate 1.

**Problem:** the platform trades Indian stocks in INR and US stocks and crypto in USD, but an account has one
cash balance with no currency, and a trade moves cash by the instrument's own price. A USD buy was therefore
charged in rupees at dollar prices, and nothing converted between the two.

**Decision:** INR is the only currency that holds money. Every amount that affects cash, orders, positions and
totals is in INR. A USD instrument is quoted in USD, as its exchange shows it, and converted to INR once, at a
known rate, before any money moves.

- **Prices are entered in the instrument's currency.** A US limit price is typed in dollars, the way the exchange
  shows it.
- **The rate is held in config for the demo** (`fx.usd-inr`), with the time it was set shown on screen. It is
  not fetched live. A rate feed can replace it later without changing anything else.
- **A margin is applied to the rate:** buys use the rate plus 0.5 per cent, and sells use the rate minus 0.5 per
  cent, so the platform does not give away the spread. The estimate shows "includes a 0.5% conversion margin".
  Margin level is config too.
- **The rate is locked when an order is placed.** The order stores the rate it was placed at, its rupee cost is
  reserved from that rate, and settlement uses the stored rate. A queued order's cash therefore does not change
  between placement and the open, and the user sees the exact rupee cost before confirming.
- **Positions keep their cost in INR**, at the locked rate of each purchase. Current value is recalculated at
  the current rate, and P&L is shown in INR.
- **Totals are in INR.** A holding is shown in its own currency with its rupee equivalent beside it.
- **Crypto (`X:`) is quoted in USD** and converted the same way.

**Why not one currency per account:** each currency's cash would need its own balance, and a conversion
between them, which is the larger change. The demo needs one balance and a visible conversion.

**Why not convert quotes once in the market-data feed:** that hides the exchange price, so a user could not
check the number against the exchange. Converting at the order keeps the exchange price visible and puts the
cost in one place.

**Consequences:** a new config value, a stored rate on each order and each position's cost, a conversion step
in the portfolio and the ticket, and currency-aware formatting in the UI. The reserve formula becomes quantity
× limit price × locked rate. The rate applies only to USD instruments.

**Not decided here:** a live rate feed, a conversion-only fee (separate from the margin), and multi-currency
cash balances, which are not needed while INR is the only money.

### 0021 — Statements: trades and cash for a date range, as a screen and as CSV; the blotter gets status tabs
**Problem:** the trades and the cash ledger are stored, but no screen or file shows them. A user at tax time
cannot get "what I bought and sold, at what price, and when" without the database. The blotter lists orders
only, and it has no way to filter them by status.

**Decision:**
- **Two read-only endpoints** on order-service, `GET /api/v1/accounts/{id}/trades` and `.../cash-ledger`, for a
  date range. The account must be the token's own; another account is refused with ACC-403, as in the portfolio.
- **CSV download** from `.../statement/trades.csv` and `.../statement/cash.csv`, with RFC 4180 quoting, and a
  guard against spreadsheet formulas: a cell that starts with `=`, `+`, `-` or `@` is prefixed with an
  apostrophe, so it opens as text.
- **Ranges** are inclusive of their last day, at most 400 days, and the end must not come before the start.
- **Financial year by default** (1 April to 31 March), which is what Indian tax filings use. The calendar year is
  offered as well.
- **PDF** is the browser's print view, saved through the print dialog. The print styles hide the navigation. A
  server-generated PDF can come later if a file must be identical each time.
- **Cash** is a separate tab, not mixed into the trades.
- **No fee column.** The platform charges no fees, so a column of zeros would suggest a charge that does not exist.
- **Blotter:** status tabs (All, Working, Filled, Rejected, Cancelled) with counts, and a search by symbol or order
  ID. Working is the NEW orders, which include queued ones. These are filters on the orders already loaded.

**Why the statement is separate from the blotter:** the blotter is the live view of orders, and it changes as
they settle. A statement is a record of what happened in a fixed period, and it must not change once issued.

**Not decided here:** capital gains. The statement lists fills; working out gains needs a cost-basis rule, such as
FIFO, which is a separate decision. The rupee values for USD fills wait for the currency work.

**Consequences:** new read-only endpoints and mapper queries, a new statements page, and a route and a nav link.
The endpoints are in `trade-api.yaml` under the tag Statements, and the trade client is regenerated from it.
A live check ran the two statement queries against the development database for account 1; both returned the
seeded trades and cash movements, oldest first.

**Known gap (closed on 8 October):** dollar fills used to be booked in an INR account with no conversion. They are
now converted at the rate locked on the order (0020), and the seed data's dollar fills carry that rate.

### 0022 — Charts: keep Lightweight Charts, use a stacked bar for allocation, and an area sparkline
**Decision:**
- **Portfolio value:** keep TradingView's Lightweight Charts, which is already in use. It is about 60 KB and draws on
  a canvas, so it suits a weak CPU and a static S3 deployment. The value line sits on a soft gradient fill, and the
  invested amount is a thin dashed line. Estimated stretches stay lighter, as before.
- **Allocation:** a stacked horizontal bar with a readout, replacing the SVG donut. A bar shows many holdings more
  clearly than a ring, and it reads better on a narrow screen. Hovering a segment dims the others and shows its share.
  The legend with percentages stays.
- **Sparklines:** kept as SVG, the lightest option for a table of instruments, now with a gradient fill under the line.

**Why not Chart.js or another library:** each adds a bundle and a second drawing model, for no gain over the charts
already in use. The app has no candlestick chart, because the stored history holds one price per point, not
open, high, low and close.

**Consequences:** the allocation's test id is now `allocation-bar`. No data or calculations changed.

### 0023 — All charts use ApexCharts; the allocation shows every position as a donut
**Supersedes 0022 in part.** 0022 kept Lightweight Charts and used a stacked bar. This entry replaces both.

**Decision:**
- **All charts use ApexCharts (MIT).** It is a heavier library than Lightweight Charts, about 160 KB, but it gives
  a polished default look: gradient areas, a datetime axis, tooltips, and a donut with a centre total. It is SVG,
  so it stays sharp at any size. It is a static bundle, so it deploys to S3 without a server.
- **Portfolio value:** an area with a gradient fill, a dashed invested line, a datetime axis, and a tooltip. The
  header, the currency tabs, the P&L and the estimate note are unchanged.
- **Allocation:** a donut with **every** position, largest first, each with its own colour. The earlier five-slice
  cap and the grouped "Other" slice are removed, because grouping hid holdings. The legend lists all of them and
  scrolls when there are many. Hovering a legend row or a slice shows its share in the readout.
- **Market sparklines:** an ApexCharts area in the same style as the value chart.
- **Removed:** the `lightweight-charts` dependency.

**Trade-off:** ApexCharts cannot colour one line segment differently by point. The value chart therefore uses a single
line colour, green at the latest value when it is above what was invested and red when below, and the estimated
stretch is noted in text rather than drawn lighter. This was the price of the cleaner look.

**Not decided:** candlesticks. The stored history has one price per point, so candles would not show real ranges.

### 0024 — Value chart and sparklines drawn in plain SVG and CSS; the donut stays on ApexCharts
**Reverted, not in use.** The code was returned to the ApexCharts state of 0023 at the owner's request. This entry records the attempt and why it was reverted.

**Supersedes 0023 in part (when in use).** 0023 moved the value chart and the sparklines to ApexCharts. That made the lines look
stepped, because ApexCharts' smooth curve overshoots between sparse points, and the library is heavy for a static
bundle on a weak machine. The donut, which looked right, stays on ApexCharts.

**Decision:**
- **Value chart and sparklines** use a shared `AreaChart` component: plain SVG, styled with ordinary CSS, with no
  charting library. The curve is a monotone cubic spline (Fritsch and Carlson), which never overshoots, so a line
  that levels off at a peak stays level instead of dipping. A null value breaks the line, so a gap is shown as a gap.
- **Axes** use round steps (1, 2 or 5 times a power of ten), so the labels read as numbers a person would choose.
- **Maths is pure:** `chart-math.ts` holds the curve and tick functions, with unit tests.
- **Donut:** unchanged, ApexCharts, showing every position.

**Why not invent points to smooth further:** a line between two readings is a guess about the value in between.
The chart draws the real readings and smooths only the curve through them, so a step in the data stays a step in
the value, just a curved one.

**Consequences:** `AreaChart` uses CSS colours (`style.stroke`), so the theme variables resolve. The value chart
still uses one colour for the whole line, as in 0023.
