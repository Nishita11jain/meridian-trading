# 0003 Consumer authorization boundary: act only on event accountId, never route parameter

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

The Notifications and Watchlists consumers subscribe to Kafka topics (`trade-events` and `market-data`) and process events asynchronously. Each event carries an `accountId` field (the owner of the trade or the account watching the symbol).

The question is: where should authorization checks live?
- Option A: Consumers check `event.accountId` and process only events for the subscribed account(s). Routes (REST endpoints) never check.
- Option B: Consumers are fully open; authorization happens only on REST routes (via token validation).
- Option C: Both consumers and routes validate, with consumers trusting the route to have filtered events upstream.

This decision shapes the security model and dictates what level of trust each layer has.

## Options considered

| Option | For | Against |
|---|---|---|
| Consumers act only on event.accountId | Strongly defensive; consumer is a security boundary; no multi-tenant confusion; event is the source of truth | Requires consumer to validate; adds latency to hot path; duplication if routes also validate |
| Routes validate, consumers trust | Simple; single point of validation; consumers stay fast and simple | Route layer becomes critical for security; if route is bypassed, consumer has no defense; events flowing from other sources (batch jobs, migrations) are unprotected |
| Both layers validate (defense-in-depth) | Belts and suspenders; each layer checks for bugs in the other | Doubles validation code; can create confusion about who is responsible |

## Decision

Consumers validate using `event.accountId`. They never use a route parameter or token to determine whose data they're operating on. Routes validate separately using the bearer token (standard Spring Security).

This is a defense-in-depth approach, but it puts the consumer as the primary security boundary. The reasoning: events come from Kafka, which is not exposed over HTTP. The consumer is a trust boundary between the external message broker and the account-specific database writes. If the route layer is compromised or bypassed, the consumer still protects data.

## Consequences

- Every consumer method has a pattern: `parse event → extract accountId → query/check against that ID → write to DB`. No multi-account confusion.
- Routes must also validate the token's account against the path parameter. This creates two layers of defense.
- Testing is simpler: each consumer test is account-scoped and doesn't require mocking the route layer.
- If a future async job (batch reconciliation, scheduled alert sweep) needs to act on events, it must also validate accountId before writing.
