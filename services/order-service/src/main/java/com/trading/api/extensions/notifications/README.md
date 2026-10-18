# Notifications Extension

## Purpose

Customer inbox for order outcomes and watchlist price alerts.

This module runs inside the Trade REST API under `com.trading.api.extensions.notifications` and owns:

- its own routes (`/api/v1/notifications/...`)
- its own persistence mapping (`notifications` table)
- its own Kafka consumer (`trade-events`, group `notification-service`)
- its own idempotent ingest rules (event id + source event uniqueness)


### Seam A: Notifications calls Preferences

- Caller module: notifications
- Callee module: preferences
- Contract type: in-process Java interface (not HTTP)
- Interface: `CustomerChannelResolver`
- Method shape:
  - `NotificationRoute resolveChannel(long accountId)`
  - response shape: `{ channel: String, contact: String | null }`

No-preference behavior:

- no stored preference row resolves to `{ channel: "IN_APP", contact: null }`
- notification is still recorded and visible in inbox (`delivery_state = SENT` for IN_APP)

Failure/latency decision for this seam:

- in-process call: no network timeout policy
- resolver exception: creation fails and the consumer path retries through Kafka error handling
- retry-exhaustion behavior follows configured `DefaultErrorHandler` policy

### Seam B: Watchlists calls Notifications

- Caller module: watchlists
- Callee module: notifications
- Contract type: in-process Java interface (not HTTP)
- Interface: `NotificationDelivery`
- Method shape:
  - `void deliver(long accountId, UUID sourceEventId, String type, String subject, String message, NotificationReference reference)`
  - response shape: `void` (fire-or-raise)

Failure/latency decision for this seam:

- in-process call: no network timeout policy
- delivery exception: market-data ingest path fails and is retried by consumer error handling
- retry-exhaustion behavior follows configured `DefaultErrorHandler` policy

## API First Contract

OpenAPI lives at:

- `contracts/api-schemas/notifications-api.yaml`

Controller and DTOs implement that contract.

## Event Ingest and Idempotency

- `TradeEventsConsumer` consumes `trade-events` with group `notification-service`.
- It handles order outcomes (FILLED, REJECTED, CANCELLED) and persists notifications.
- Offset is acknowledged only after durable record (`ingestTradeEvent`) succeeds.
- Replay safety:
  - claim in `processed_events` by `(consumer_name, event_id)`
  - unique `notifications.source_event_id`

## Consumer Group Identifier

- Notifications group id: `notification-service`
- Explicitly not shared with watchlists (`watchlist-service`) or executor (`trade-executor`), so offsets stay independent.

A replayed event is therefore a no-op (no second notification).

## Channel Resolution and Delivery State

For every notification creation, channel is resolved fresh from customer preferences via `CustomerChannelResolver`.
The resolved channel is stored on the notification row.

Delivery state is tracked as:

- `QUEUED` for async external channels (EMAIL/SMS/PUSH)
- `SENT` for in-app inbox delivery
- `FAILED` when no usable route can be handed off

## Security and Ownership

All routes are bearer-token protected and account-scoped.

- path `accountId` must equal token `accountId`
- cross-account access is refused (`ACC-403`)
- unknown-or-not-owned notification ids return `NTF-404`

Notification history is readable only by its owning account.

## Watchlists Entry Point

Watchlists triggers notifications through the in-process `NotificationDelivery` interface.
No customer-facing route exists to post arbitrary destinations or trigger sends directly (prevents SSRF-style misuse).
