# 0026 A strategy orders at the ask or the bid, logs PLACED not FILLED, and keys on the event id

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

A live review found three bugs in Automated Strategy Execution. First, `StrategyExecutionService` sent the last trade price as the order's limit. `FillRule` in executor-service only fills a BUY at or above the ask and a SELL at or below the bid, so a strategy order was rejected on `PRICE_NOT_MET` almost every time it fired. Second, the execution log wrote `FILLED` the moment the order route accepted a request, but the route answers `NEW` -- a fill happens later, asynchronously, in the executor -- so the log claimed an outcome that had not happened. Third, the idempotency key sent with the order included `System.nanoTime()`, which is different on every call and so could never actually detect a repeat of the same trigger.

## Options considered

| Option | For | Against |
|---|---|---|
| Keep sending the last trade price | No change | Still almost always rejected; the feature does not work |
| Send the ask/bid as the order price | Matches what `FillRule` actually checks | None found |
| Log `FILLED` on acceptance, as before | No schema change | A stored audit row states something false |
| Log `PLACED` on acceptance | Honest about what is known at that point | A migration to widen the allowed outcome values |
| Keep the nanoTime-based key | No change | Not actually an idempotency key |
| Build the key from the strategy id and the Kafka event id | A real repeat of the same event produces the same key | None found |

## Decision

Price a BUY at the quote's ask and a SELL at its bid, read from the `market-data` payload, and apply the spend cap against that price rather than the last trade price. Log `PLACED`, not `FILLED`, when the order route accepts the request (migration `022` widens `ck_strategy_execution_log_outcome` to `PLACED`, `REJECTED`, `SKIPPED`). Build the idempotency key as `strat-<strategyId>-<eventId>`. Separately, re-read the strategy's status immediately before calling the order route: `findActiveBySymbol` reads once per quote, so a disable requested while that quote's strategies were being evaluated must still be honoured at the moment of firing, not only on the next quote.

## Consequences

- A strategy's orders can now actually fill, under the same price rule a human's order would.
- The execution log states only what is known at the time it is written; a real fill is not recorded here at all, since this module does not consume `trade-events`.
- A Kafka redelivery of the same quote reuses the same idempotency key rather than minting a new one every time.
- `maxConsecutiveFailures` still counts only order-placement failures, never a later executor rejection, which the module's own README already notes as a gap to revisit.
