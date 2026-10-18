# 0014 EMAIL, SMS and PUSH notifications are recorded as QUEUED and nothing is sent

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

The brief asks for delivery on at least one channel and allows a logging stub for a channel we cannot provision. We have no email, SMS or push provider.

## Options considered

| Option | For | Against |
|---|---|---|
| Mark every notification `SENT` | Looks complete | Untrue for channels that sent nothing |
| IN_APP is `SENT`, the others are `QUEUED`, or `FAILED` when there is no contact | Each state says what actually happened | The other channels look unfinished |

## Decision

The channel is resolved from Preferences on every send and recorded on the notification. IN_APP is the only channel that is delivered, so it is `SENT`. EMAIL and SMS are `QUEUED`, or `FAILED` when the customer has not given an address or number. PUSH is `QUEUED` as well. `QUEUED` means "recorded for delivery"; nothing leaves the platform.

## Consequences

- A customer who picks EMAIL sees the notification in the app inbox but gets no email.
- Adding a real provider means a sender that moves `QUEUED` rows to `SENT` or `FAILED`; the table already supports it.
