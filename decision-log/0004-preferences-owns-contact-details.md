# 0004 Preferences owns contact details; Notifications owns channel choice only

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

Two modules need customer contact information: Preferences (to let customers set their email address and phone number) and Notifications (to deliver alerts).

The question is: where should contact details be stored?
- Option A: Preferences stores contact details; Notifications queries Preferences when it needs to send.
- Option B: Notifications stores a copy of contact details along with channel preferences.
- Option C: Neither; contact details are owned by a third module (e.g., Customer Service).

In practice, once contact details are set, they change slowly (annual re-verification). The channel choice (email vs. in-app) changes more often.

## Options considered

| Option | For | Against |
|---|---|---|
| Preferences owns and queries Notifications | Clear ownership; contact details are in one place; Notifications is read-only on contact | Cross-module read; Notifications depends on Preferences data freshness; if Preferences is down, Notifications can't send email |
| Notifications owns a copy | Notifications is independent; no cross-module reads; can send email even if Preferences is down | Duplication; risk of stale copies; who updates the copy? Sync burden |
| Third module (Customer Service) owns | Clean separation; Preferences and Notifications both query; can share | Adds a dependency; requires new module; MVP doesn't need it |

## Decision

Preferences owns the email address, the phone number and the chosen channel, all in `customer_preferences`. Notifications owns the notification itself and its delivery state (`QUEUED`, `SENT`, `FAILED`, see decision 0014). On every send, Notifications asks `CustomerChannelResolver` which channel and contact to use, through the interface and not over HTTP (decision 0001), and records the answer on the notification.

Contact details are stored as plain text (security review, A02). They are not encrypted. That is accepted for now.

## Consequences

- Preferences is the one authoritative store for email, phone and channel. A change there applies to the next notification, because nothing is cached.
- If the lookup fails, delivery fails and the Kafka message is retried and finally dead-lettered (decision 0009), so a notification is not lost silently.
- Contact details are plain text, which the security review lists as an accepted finding.
