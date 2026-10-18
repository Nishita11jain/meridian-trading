# 0002 Preferences without a row default to IN_APP notification channel

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

The Preferences module stores user display settings (theme, currency, default account) and notification delivery preferences (where alerts should be sent: in-app, email, or SMS). If a customer has never opened the Preferences page, no row exists for them.

The Notifications consumer needs to know which channel to use. It calls `CustomerChannelResolver.resolveChannel(accountId)` to look up the preference. The question is: what should the resolver return if no preference row exists?

Default behavior is not critical for MVP (notifications are in-app-only at launch), but the design pattern matters for security review and future scalability.

## Options considered

| Option | For | Against |
|---|---|---|
| Return IN_APP (default) | Safe; customer sees notifications; no data loss; forces no schema change | Notifications don't reflect user intent (if they prefer email, they get in-app instead) |
| Return null / throw exception | Forces customer to set preference; explicit opt-in | Notifications silently dropped if preference missing; poor UX for new customers |
| Send to all channels | Guarantees delivery; customer gets notification somehow | Over-communicates; may spam email before customer sets it up |

## Decision

The resolver returns `IN_APP` if no preference row exists. This is safe: the customer sees the notification, and no event is lost. It reflects a reasonable default (most users open the app more often than checking email). If a customer later sets a preference, future notifications use the stored channel.

The alternative (null / error) was rejected because it creates a footgun: a missed database row means a missed alert, and there's no way to recover it. The schema is simple and has no room for corruption.

## Consequences

- New customers get in-app notifications by default until they set a preference. This is the intended behavior for the demo (all notifications are in-app anyway).
- The Preferences row is not required for Notifications to function. If contact details are stored elsewhere in the future, Preferences becomes truly optional.
- The lookup is one indexed read per notification and is done on every send, so a changed preference takes effect on the next message.
