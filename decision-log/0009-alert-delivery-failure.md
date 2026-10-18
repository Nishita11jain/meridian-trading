# 0009 A price alert whose delivery raises rolls back with its quote and is retried

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-07 |
| Decided by | Team 5 |

## Context

Watchlists hands a crossed price alert to Notifications through the `NotificationDelivery` interface (see 0001). That call can raise, for example when the database is unavailable or Preferences does not answer. `MarketDataService.ingestQuote` marks the alert triggered and calls `deliver` inside one transaction with the quote claim, price upsert and history insert. We had to decide what happens to the alert when delivery fails.

## Options considered

| Option | For | Against |
|---|---|---|
| Catch the failure, log it, keep the alert marked triggered | The quote pipeline never stalls on Notifications | The customer is never told and the alert cannot fire again: a silent loss, which the brief says does not meet the criterion |
| Catch the failure and write a retry row for a separate job | Quote ingestion is isolated from Notifications | A second mechanism to build, monitor and secure, and the alert and its retry can drift apart |
| Let it raise so the transaction rolls back and the quote is redelivered | One mechanism: the claim, the triggered flag and the delivery commit or fail together; Kafka's `DefaultErrorHandler` retries with backoff and then dead-letters to `market-data.DLT` | A persistent Notifications fault delays quotes for that partition until retries are exhausted |

## Decision

Let the failure raise. Because the event claim, `markTriggered` and the delivery share a transaction, a failed delivery leaves the alert active and the quote unclaimed, so the redelivered quote crosses again. The notification source id is derived from (event id, alert id), so a retry that had partly succeeded cannot send twice. After retries are exhausted the message goes to the dead-letter topic where it is visible, not lost.

## Consequences

- No alert is dropped silently and none is delivered twice.
- A prolonged Notifications outage slows market-data ingestion for the watchlist group only; the other consumers use their own groups and offsets.
- Alert evaluation should stay cheap, since it runs inside the quote transaction.
- If outages become common, revisit the retry-row option.
