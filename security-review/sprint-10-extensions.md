# Security review: Sprint 10 extensions

One review for all six modules that sit inside the Trade REST API: Preferences, Notifications, Watchlists and
Alerts, Portfolio, Trade Advice and Strategy Execution. They share one process, one signing key and one database
connection, so most findings land on several modules at once and the row says which.

## Header

| Field | Value |
|---|---|
| Service | Trade REST API extension modules, Sprint 10 |
| Reviewed by | Team 5 |
| Date of review | 2026-10-07 |
| Commit reviewed | 8cc10f8, plus uncommitted fixes made during this review (see A04, A05, A06 and A08) |
| Version of the OWASP Top Ten used | 2021 |

## Categories

| Category | In scope | Finding | Disposition |
|---|---|---|---|
| A01 Broken access control | Yes | Every route the modules add takes the account in the path (or reads it from the token) and compares it with the `accountId` claim; a mismatch is `ACC-403`. Checked live on 2026-10-07 with a token for account 38: notifications, preferences, portfolio and strategies for account 1 all returned `ACC-403`. Watchlists answer `WL-404` instead of 403 on purpose, so a caller cannot tell which watchlist ids exist (decision 0005). The two consumers have no token, so they check the `accountId` inside the event before acting (decision 0003). **Where it landed differently:** Strategies are the one module that acts for a customer with nobody signed in. Its token is minted in code, and the server cannot tell it from a customer token apart from the `sub` (`strategy-engine:<id>`). | Mitigated. The strategy token lives 30 seconds, is minted per order and is never stored (`InternalOrderClient.java`). `OrderController` now logs every order that arrives on a `strategy-engine:` token, so those orders can be told apart from a person's. Residual risk is that anyone holding `JWT_SECRET` can mint any account's token, which is the same exposure the auth service already has. Accepted, recorded in decision 0010. |
| A02 Cryptographic failures | Yes | Contact email and phone are kept as plain text in `customer_preferences`. Passwords never reach this service. Tokens are HS256 signed with a shared secret read from the environment. A Fauxnance API key and the local Postgres password are committed in `executor/src/main/resources/application.yml`, so they are in the git history. | Accepted. Contact details are not encrypted at rest; the database is only reachable inside the stack (decision 0004). Revisit before any real deployment. The committed key and password are an accepted finding (decision 0012): the key belongs to the whole cohort and cannot be rotated by us, and rewriting history this close to the showcase was judged worse. Exposure is a 2,000-requests-a-day key and a development database that listens on all interfaces of this machine. |
| A03 Injection | Yes | The mapper XML files contain no `${}` substitutions at all (grepped under `resources/mapper`); every value is bound with `#{}`. The annotation mappers (candles, alerts, advice) bind their parameters the same way. Portfolio, Notifications and Watchlists use MyBatis, not JPA. | Mitigated. Nothing found. Re-run the grep whenever a mapper is added. |
| A04 Insecure design | Yes | Two real problems found. (1) **A rejected order froze the whole account.** `placeOrder` locked the account row `FOR UPDATE`, then recorded the rejection in a separate transaction whose foreign key needs the same row. The two waited on each other forever, holding two pooled connections, and every later write for that account (new alerts, orders) hung behind them. Found by the live run today with an order for 5,000 shares. A customer who keeps sending orders they cannot afford could use up the connection pool for everyone. (2) Notification delivery: only IN_APP is delivered. EMAIL and SMS are recorded as `QUEUED` and no provider sends them. | (1) Fixed. `AccountMapper.xml` now locks with `FOR NO KEY UPDATE`, which still serialises concurrent orders but does not block the foreign-key check. Checked live: the rejection returns `ORD-400` at once and the alert calls that hung before now succeed. The existing test `anOrderTheAccountCannotAffordIsRefusedAndTheRefusalIsRecorded` passes with the fix and hangs with the old lock, but it only runs when the Postgres variables are set. Not yet committed. (2) Accepted, as the brief allows a logging stub provided the channel is really resolved and recorded; `QUEUED` says honestly that nothing was sent. |
| A05 Security misconfiguration | Yes | (1) `/swagger-ui/**` and `/v3/api-docs/**` were public, so the full route list was readable without a token. (2) CORS allows only `http://localhost:4200` and lists GET, POST, PUT, DELETE, not PATCH, which `PATCH /watchlists/{id}` needs if the browser ever calls it cross-origin. (3) `application.yml.example` was missing `app.internal-base-url`, so a fresh checkout could not start the service, because the strategy client requires it. (4) The portfolio health check answered 401 and sat under `/api/v1/portfolio`, although `portfolio-api.yaml` declares `GET /health` public. (5) `GEMINI_API_KEY` comes from the environment and empty means the assistant is off. | (1) Mitigated. The docs stay public locally, because an earlier sprint requires the running service to serve its contract, and `app.docs.public=false` puts them behind the token. A test proves the closed mode. The switch must be set false in any deployed profile (decision 0011). (2) Accepted; the dev proxy keeps calls same-origin. (3) Fixed in the example file. (4) Fixed: exactly `GET /health` is permitted, it only reports postgres and fauxnance status, and a test shows the portfolio routes still return 401 (decision 0011). (5) Mitigated. |
| A06 Vulnerable and outdated components | Yes | Scanned on 2026-10-07. `npm audit --omit=dev` finds nothing in the frontend or the auth service; the 17 and 38 findings in the full trees are all dev tooling (Angular build, Jest, Playwright, the OpenAPI generator). For the Java services the dependency list was checked against OSV. It found Jackson (two high), Netty (one critical in handler, one high), the Postgres driver (high), commons-lang3, log4j-api and lz4-java behind patch releases. Tomcat 10.1.55 has three critical advisories (DIGEST and FORM authentication, an access control flaw) and spring-webmvc 6.2.19 has two (server-sent events, XsltView). The Gemini assistant sends a customer's holdings to Google; the key is server-side and name and contact details are left out (decision 0008). | Fixed for Jackson, Netty, the Postgres driver, commons-lang3, log4j and lz4-java by version overrides in both `pom.xml` files; 440 order-service and 125 executor tests pass on them. Mitigated for Tomcat: 10.1.58 is not on Maven Central yet, and the service uses bearer tokens only, with no DIGEST or FORM login. Mitigated for spring-webmvc: the fix is in Spring 7, and the code uses neither `SseEmitter` nor `XsltView` (searched). Both stay on the list until the parent is bumped. CI now runs `npm audit --omit=dev --audit-level=high` for the two Node services; there is still no Java scanner in the pipeline. |
| A07 Identification and authentication failures | Yes | One verifier for the whole service (`SecurityConfig`): signature, expiry and issuer are checked and every failure returns the same `AUTH-401`. Verified live earlier today: no token and a wrong token both gave 401. The auth service limits login attempts. | Mitigated. No finding beyond the strategy token already listed under A01. |
| A08 Software and data integrity failures | Yes | Both consumers (Notifications, Watchlists) and the strategy consumer record the event id in `processed_events`, so a replay does nothing. Checked live: sending the same order twice with one idempotency key gave one order and a `409`, and the fills were notified once each. Consumers retry three times at one-second intervals. Before today nothing was configured for what came after, so a message that still failed was dropped and the offset moved past it. | Fixed. After the retries the message is published to `<topic>.DLT` (`trade-events.DLT`, `market-data.DLT`, which `create-topics.sh` already creates), then the offset moves on. A unit test covers the topic naming. Not yet exercised against a live failing message. |
| A09 Security logging and monitoring failures | Yes | Consumer failures and refused strategy triggers are logged. Auth failures return a uniform body and are not counted. Preference changes are not logged. The `ACC-403` body for another customer's account reads "account is not active". That is the wording `trade-api.yaml` defines for both a foreign and an inactive account, so the response cannot be used to find out which accounts exist. | Accepted. Nothing sensitive is logged; no alerting exists. The message stays as the contract defines it. |
| A10 Server-side request forgery | Yes | **Checked by name.** The channel is one of IN_APP, EMAIL, SMS or PUSH, validated in `PreferencesService`, and a contact is an email address or a phone number, never a URL, so the platform never posts to a customer-chosen destination. The only outbound calls go to the Gemini endpoint (fixed), Fauxnance (fixed base URL from configuration) and the service's own `/api/v1/orders` (`app.internal-base-url`, configuration, not request data). | None found. Mitigated by design: if a webhook channel is ever added, its destination must be validated against an allow-list first. |

## The two risks named in the brief

- **An internal path a customer's token can reach.** The platform has no separate internal route. Notifications,
  Watchlists and Strategies call each other as Java interfaces inside the process (decision 0001), so there is no
  HTTP path to protect. The one HTTP self-call, the strategy engine to `POST /api/v1/orders`, uses the same public
  route and the same checks as a customer, which is deliberate (decision 0010): its limits are the route's own
  validation, the per-strategy spend and position caps, and the failure counter.
- **A customer-supplied destination the platform posts to.** None exists; see A10.

## Where the modules differ

- Access control is by `ACC-403` everywhere except Watchlists (`WL-404`).
- Only Strategies acts for a customer with no one signed in. Preferences and Portfolio only ever answer a signed-in
  caller. Notifications and Watchlists act from Kafka but never touch a route; they rely on the account id inside
  the event.
- Idempotency is by `processed_events` for the three consumers, and by the order's idempotency key for placement.
- Abuse limits: alerts and strategies are capped per account, and the assistant is rate-limited.

## Evidence

| Check | How it was performed | Result |
|---|---|---|
| Cross-account access | Live: token for account 38 against account 1 on notifications, preferences, portfolio and strategies | All `ACC-403` |
| Order replay | Live: same idempotency key sent twice | One order row, second call `ORD-409` |
| Rejection path | Live: BUY of 5,000 MSFT | Hung before the fix; `ORD-400` immediately after |
| Notification channel | Live: set EMAIL, filled an order, set IN_APP, filled another | First recorded `EMAIL / QUEUED`, second `IN_APP / SENT`; the rejected order also produced a notification |
| Alerts on live quotes | Live: alerts at ABOVE 520 and BELOW 540 on MSFT, last price 527.58 | Both triggered on the next poll and were delivered through Notifications |
| Strategy and advice routes | Live: create, disable and read the log of a strategy; request a signal for MSFT | All answered; signal was HOLD with the SMA20/SMA50 reasoning |
| SQL substitutions | Searched `resources/mapper` for `${` outside `#{}` | None |
| Strategy and advice tests | `mvn test` on the strategy and advice test classes | 31 passed |
| Dependency scan | `npm audit` in `frontend` and `services/auth-service`; OSV batch query over the 164 Maven artifacts on the order-service test classpath, before and after the overrides | Node: none in shipped code. Java: 10 artifacts affected before, Tomcat and spring-webmvc remain (see A06) |
| Health and docs access | `DocsAccessWebMvcTest`, and curl against the restarted service | Portfolio health 200 without a token, advice health and alerts 401, docs open by default and 401 when `app.docs.public=false` |
| Full suites on the patched dependencies | `mvn test` in both services with the database variables set | order-service 440 passed, executor 125 passed |

## Outstanding items

| Item | Owner | Target date |
|---|---|---|
| Commit the `FOR NO KEY UPDATE` fix and make CI run the database integration tests, which skip without the Postgres variables | Nishita | 2026-10-09 |
| Send one failing message through a running consumer and confirm it lands on the `.DLT` topic | Nishita | 2026-10-09 |
| Move to a Spring Boot release that carries Tomcat 10.1.58 or later and Spring 7 fixes, then drop the version overrides in both `pom.xml` files | Partha | before cloud week |
| Add a Java dependency scan to CI | Partha | before cloud week |
| Set `app.docs.public=false` in the deployed profile | Partha | before cloud week |
| Replace the Fauxnance key when the cohort key can be reissued (`executor/application.yml` is deleted, but the key stays in git history) | Partha | before cloud week |
