# Normalisation Notes

## Third normal form

- `instruments`: every non-key attribute (`instrument_name`, `asset_class`,
  `currency`, `tradable`) depends only on `symbol`. No transitive dependency.
- `accounts`: every non-key attribute depends only on `id`. `account_id` is a
  candidate key (unique, not the primary key) — the two are kept distinct
  per the domain brief ("an internal key... and a customer-facing reference...
  not interchangeable"), so no attribute is duplicated across them.
- `orders`: every non-key attribute depends on `id` alone. `account_id` and
  `symbol` are foreign keys, not copies of data owned by `accounts` or
  `instruments` (e.g. we do not store `holder_name` or `instrument_name` on
  the order row).
- `positions`: the composite key `(account_id, symbol)` determines `quantity`
  and `average_cost`, and neither attribute depends on only part of that key.

## Deliberate denormalisation: `positions`

`positions` is derived state. It could, in principle, be dropped entirely
and rebuilt at query time by replaying every `FILLED` order for an account
and instrument — that's exactly the reconciliation our seed data satisfies
(see `seed/positions.csv` vs. the `FILLED` rows in `seed/order.csv`).

We store it anyway, as a deliberately denormalised summary table, because:

- Query 3 (current holdings, rendered on every dashboard load) cannot afford
  to replay full order history on every page view.
- The replay computation (weighted-average cost across every fill) is not
  expensive per account, but is unnecessary work to repeat on every read
  when almost all reads ask "what is the current state", not "how did we
  get here".

The cost of this decision: `positions` must be kept in sync with `orders`
whenever an order fills. In Sprint 3 this sync happens once, at seed time,
by hand. From Sprint 7, the Trade Executor is responsible for updating the
matching `positions` row in the same transaction that marks an order
`FILLED` — this is called out explicitly so that whoever writes that service
knows `positions` is not self-maintaining.

A `positions` row with `quantity = 0` (see `ETP-4102 / QQQ` in the seed data)
is kept rather than deleted when a holding is fully sold, preserving the
account's cost-basis history for that instrument rather than losing it the
moment the position closes.

## Migration 007: `trades` and `account_transactions`

- `trades`: every non-key attribute (`quantity`, `execution_price`,
  `broker_reference`, `executed_at`) depends only on `id`. `account_id`,
  `symbol` and `side` are deliberately **not** duplicated here — they are
  reachable via `trades.order_id -> orders.account_id / orders.symbol /
  orders.side`, so storing them again on `trades` would be redundant data
  that could drift from the order it belongs to. `order_id` is `UNIQUE`
  rather than a plain foreign key because partial fills are out of scope
  (an order reaches exactly one terminal state), so the relationship is
  `ORDERS 1 --- 0..1 TRADES`, not one-to-many.
- `account_transactions`: an append-only ledger keyed by `id`, with every
  attribute (`transaction_type`, `amount`, `reference_type`, `reference_id`,
  `created_at`) a fact about that one transaction. It intentionally has no
  `balance_after` column: the current balance is `accounts.cash_balance`,
  and storing a second, derived copy of it on every transaction row would
  be a value fully determined by other rows (the running sum of prior
  transactions) rather than an independent fact — exactly the kind of
  computed/transitive dependency 3NF asks us to avoid. `reference_type` /
  `reference_id` is a loose pointer (e.g. `ORDER` / an `orders.id`) rather
  than a real foreign key, because it can point at different tables
  depending on `transaction_type`.

## Migration 009: `market_price`, `price_history`, `watchlists`, `watchlist_items`, `price_alerts`

Created early during the Sprint 3 database work for the Sprint 10
(Watchlists and Price Alerts) extension — see `DecisionLog.md`.

- `market_price`: keyed by `symbol`, every non-key attribute (`last_price`,
  `bid`, `ask`, `currency`, `change`, `change_percent`, `previous_close`,
  `market_state`, `stale`, `quote_as_of`) is a fact about that symbol's
  latest quote, not about any other table. It is deliberately the same kind
  of denormalisation as `positions`: it could be rebuilt by taking the most
  recent `price_history` row per symbol, but the live-price read path
  cannot afford that scan on every lookup.
- `price_history`: keyed by its own `id`, `symbol` is a foreign key rather
  than a copy of any `instruments` attribute, and every other column
  (`price`, `bid`, `ask`, `quote_as_of`, `recorded_on`) is a fact about that
  one tick. No transitive dependency.
- `watchlists`: keyed by its own `id`; `account_id` is a foreign key and
  `name` depends only on the watchlist itself, not on the account (two
  different accounts can both have a watchlist named `core`).
- `watchlist_items`: the composite key `(watchlist_id, symbol)` determines
  only `added_on` — there is no other non-key attribute to depend
  partially on either half of the key.
- `price_alerts`: keyed by its own `id`. `account_id` and `symbol` are
  foreign keys, not copies of `accounts`/`instruments` data. `status`,
  `triggered_on` and `triggered_price` are facts about that one alert's
  lifecycle, not derived from another row — nothing elsewhere in the schema
  records when an alert fired, so this is not a redundant copy of anything.

## Known, temporary redundancy: `orders.executed_price` / `orders.filled_quantity`

Migration 006 added `executed_price` and `filled_quantity` directly to
`orders`. Migration 007 adds `trades`, which expresses the same two facts
(`execution_price`, `quantity`) for a filled order without the redundancy,
plus makes `filled_quantity` unnecessary in the first place, since the
platform doesn't support partial fills. The two `orders` columns are **not**
dropped in migration 007, because `trade-api-service`'s `OrderMapper.xml`
still reads and writes `executed_price` directly in `insert`,
`findHistoryByAccount` and `findById`. Retiring those columns from `orders`
requires updating that mapper (and the Java code around it) to read fill
data from `trades` instead, which is a follow-up change, not a schema-only
one.