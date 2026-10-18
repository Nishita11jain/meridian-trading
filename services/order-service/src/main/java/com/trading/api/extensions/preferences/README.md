# Preferences Extension

## Purpose

Customer personalization inside the Trade REST API (`/api/v1/preferences/...`).

This module is the single owner of:

- notification channel route (`IN_APP`, `EMAIL`, `SMS`, `PUSH`)
- contact detail used by channel routing
- display currency, theme, and avatar key


### Seam A: Notifications calls Preferences

- Caller module: notifications
- Callee module: preferences
- Contract type: in-process Java interface (not HTTP)
- Interface: `CustomerChannelResolver`
- Method shape:
  - `NotificationRoute resolveChannel(long accountId)`
  - response shape: `{ channel: String, contact: String | null }`

When no preference row exists:

- response is `{ channel: "IN_APP", contact: null }`
- behavior is deterministic: notification delivery falls back to in-app and continues

Why this seam is internal:

- no borrowed customer token
- no customer-reachable internal endpoint
- no module reaches another module's tables directly

### Failure/Latency Policy (for this seam)

- The call is in-process, so there is no network timeout policy.
- If `resolveChannel` raises, notification creation fails in that transaction and the Kafka consumer path retries by its configured error handler.
- Retry-exhaustion behavior is whatever `DefaultErrorHandler` policy is configured for this service instance.

## Access Control

Preferences HTTP routes are customer-scoped:

- path `accountId` must match token `accountId`
- mismatch is refused with `ACC-403`

No internal seam method is exposed as a customer route.
