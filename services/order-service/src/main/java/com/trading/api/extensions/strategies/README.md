# Automated Strategy Execution

Sprint 10 stretch extension (second of two, built after Trade Advice & Signals and only once the
four mandatory modules met their criteria, per the stretch catalogue's own rule).

## What it does

A customer configures a rule once -- a moving-average crossover or a Bollinger-band condition, on
one symbol, one side (BUY or SELL, never both) -- and the platform evaluates it against every
market-data quote for that symbol and places a real order when it fires, with nobody confirming.

## Identity

A strategy fires from a Kafka listener thread with no customer logged in. It places its order
through this same service's own `POST /api/v1/orders`, over real HTTP, using a short-lived (30s)
internal JWT minted at the moment of firing -- never a direct method call into `OrderService`, and
never a borrowed customer session. See `decision-log/0010-strategy-engine-internal-identity.md` for
why.

## Data flow

- `StrategyMarketDataConsumer` -- its own Kafka consumer group, `strategy-service`, on `market-data`.
  Idempotent on eventId via the shared `processed_events` ledger, same as the Watchlists and
  Notifications consumers.
- `StrategyExecutionService` -- reads `ACTIVE` strategies for the quote's symbol **fresh from the
  database on every single quote**, never cached, so disabling a strategy takes effect on its very
  next evaluation. Builds a rolling price window from `price_history` (the same table Watchlists'
  consumer already populates) plus the just-arrived quote, and asks `StrategyEvaluator` whether the
  condition just crossed.
- `StrategyEvaluator` -- pure, side-effect-free. A crossing event only triggers the strategy
  configured for that side: a BUY strategy never reacts to a downward cross, even on the same
  symbol a SELL strategy is also watching.
- On a trigger: a max-spend check and (for BUY) a max-position check run **before** any order is
  attempted; a cap stopping an order is logged as `SKIPPED` and never counts toward the
  consecutive-failure circuit breaker, which only watches real order-placement failures.
- `InternalOrderClient` places the order via `POST /api/v1/orders`, pricing a BUY at the quote's ask
  and a SELL at its bid -- the same prices `FillRule` in executor-service actually fills against,
  not the last trade price, which would miss the fill condition on almost every attempt. The route
  answers `NEW`, not `FILLED` (a fill happens later, asynchronously, in the executor), so an
  accepted order resets the strategy's failure count and is logged `PLACED`; a failure (a business
  rejection or a transport failure) increments the count, and the strategy auto-disables once it
  reaches its own configured `maxConsecutiveFailures`. Status is re-read immediately before placing,
  so a disable requested mid-evaluation still takes effect before the order goes out.

## Tables

- `trading_strategy` -- one row per configured rule, including its own required risk limits
  (`max_spend`, `max_position_size`, `max_consecutive_failures`) and its live failure count/status.
- `strategy_execution_log` -- one row per evaluation that fired a condition, whatever the outcome
  (`PLACED`, `REJECTED` or `SKIPPED`), so a customer can see exactly when and why an order was
  placed, skipped or rejected.

## Access control

Every CRUD route addresses an account in the path and compares it to the verified token's
`accountId` claim; a mismatch is `ACC-403`. A strategy id that does not belong to the account is
`STRAT-404`. The account's strategy count is capped (`strategies.max-per-account`, default 10) so
one account cannot fill the table and turn the market-data consumer -- which runs inside the
service that places orders -- into the thing that takes the platform down; the same reasoning as
Watchlists' alert cap.
