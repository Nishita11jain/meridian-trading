# Watchlists Extension

## Purpose

Watchlists, quote read model, and price-alert evaluation inside the Trade REST API.

This module owns:

- watchlist and alert routes under `/api/v1/watchlists/...`
- market-data consumer (`market-data`, group `watchlist-service`)
- alert crossing detection and trigger lifecycle


### Seam B: Watchlists calls Notifications

- Caller module: watchlists
- Callee module: notifications
- Contract type: in-process Java interface (not HTTP)
- Interface: `NotificationDelivery`
- Method shape:
  - `void deliver(long accountId, UUID sourceEventId, String type, String subject, String message, NotificationReference reference)`
  - request shape:
    - accountId: numeric account surrogate key
    - sourceEventId: idempotency key for delivery
    - type/subject/message: customer-facing notification text
    - reference: `{ orderId?: UUID, alertId?: Long, symbol?: String }`
  - response shape: `void` (fire-or-raise)

No-preference behavior (through Notifications -> Preferences seam):

- if customer has no preference row, route resolves to `IN_APP`
- alert notification is still recorded and shown in inbox

Why this seam is internal:

- no HTTP hop through customer-facing routes
- no borrowed customer token between modules
- no direct table reach-through into notifications persistence

### Failure/Latency Policy (for this seam)

- The call is in-process, so there is no network timeout policy.
- If `deliver(...)` raises while ingesting a quote, quote processing transaction fails and message is retried by consumer error handling.
- Retry-exhaustion behavior follows configured `DefaultErrorHandler` policy.

## Consumer Group Identifier

- This module's group id is `watchlist-service`.
- It is not shared with any other consumer.
- Offset isolation is therefore independent from:
  - `trade-executor` (executor service)
  - `notification-service` (notifications extension)
