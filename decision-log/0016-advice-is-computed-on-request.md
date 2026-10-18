# 0016 Trade advice is computed on request from cached daily candles, and says only what one method says

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

A signal is a view on an instrument, not an order. Daily candles do not change during the day, and the Fauxnance quota is shared by the whole cohort. The brief warns that a generated number presented as a recommendation is a product problem before it is an engineering one.

## Options considered

| Option | For | Against |
|---|---|---|
| Recompute for every symbol on a schedule | Always fresh | Spends quota on symbols nobody asked about |
| Compute when the customer asks, cache the candles and the result | Spends quota only on interest, and repeated clicks cost nothing | The first request is slower |
| Recompute on every quote | Reacts instantly | Expensive and mostly noise |

## Decision

Compute on request. The explicit "analyse" action fetches daily candles, caches them with an expiry (`advice.cooldown-hours`, default 24), and stores the result. A second request inside the cooldown is served from the cache with no Fauxnance call. If a refresh fails and an older result exists, that is returned marked `stale`; with nothing cached the answer is `ADV-503`. The only method is an SMA20/SMA50 crossover, and the response carries its direction, a strength between 0 and 1, and a sentence naming the two averages. Advice is only given for symbols the customer holds or watches.

## Consequences

- It describes what two moving averages say about past prices. It does not predict.
- Candles are cached per symbol, not per account, because a signal is a property of the instrument.
