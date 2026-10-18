# 0007 An order for a closed market is queued, not refused

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-07 |
| Decided by | Team 5 |

## Context

Indian, US and crypto markets have different hours, and a customer can place an order at any time. The first version refused an order when its market was closed, using the error `MKT-409`. That meant a customer who tried at 8pm had to come back and retry in the morning.

## Options considered

| Option | For | Against |
|---|---|---|
| Refuse with `MKT-409` | Simple, and what the first version did | The customer has to remember and retry |
| Store it as `NEW` and settle it when its own market opens | Nothing is lost, and cash or shares are reserved from the moment of placing | Needs a job that finds the waiting orders, and more state to explain |

## Decision

Queue it. Placement stores the order as `NEW` with its cash or shares reserved, exactly as for an open market. The executor's `QueuedOrderReleaser` runs every 30 seconds, finds `NEW` orders whose own market is now open and settles them. The long version of the reasoning is in `DecisionLog.md` under "Orders for a closed market are queued, not refused".

`MKT-409` is still in the error catalogue and `TradingHours.requireOpen` still exists, but order placement no longer calls it. It is kept for the contract, not used.

## Consequences

- The customer sees the order as working (`NEW`) and is notified when it fills (decision 0015).
- A queued order holds cash until it settles or is cancelled.
- Anything that lists orders has to treat `NEW` as "waiting", not "just placed".
