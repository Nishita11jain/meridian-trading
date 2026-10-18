# 0006 Portfolio prices come from the market_price table, not a Fauxnance call

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

To value a holding, Portfolio needs a current price for each symbol. The brief points at Fauxnance's batch quotes endpoint. The executor's poller already calls that endpoint on a schedule and publishes each quote to `market-data`, and the Watchlists consumer stores the latest one per symbol in `market_price`.

## Options considered

| Option | For | Against |
|---|---|---|
| Call Fauxnance from Portfolio, batched and cached | Matches the brief literally | A second caller on the shared 2,000-a-day key, and a Fauxnance outage would take the portfolio page down with it |
| Read `market_price` | No extra quota, no new failure mode, and the poller already keeps it fresh | Portfolio depends on the table the Watchlists consumer fills, and a stopped poller means old prices |
| Keep a separate price table for Portfolio | Portfolio owns its data | Another writer to keep in step for no real gain |

## Decision

Read `market_price`. Each priced position carries `lastPrice`, `priceAsOf` and a `stale` flag taken from the stored quote, so an old price is shown and marked, not hidden. If no held symbol has any price the route answers `503 MKT-503`.

## Consequences

- Portfolio never calls Fauxnance and cannot spend the quota.
- Portfolio reads, and never writes, a table another module owns. Review that boundary whenever either side changes.
- A stopped poller does not break the page; it makes every price stale.
