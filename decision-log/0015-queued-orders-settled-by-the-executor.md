# 0015 Queued orders are settled by the executor, and their outcome is published like any other

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

Orders for a closed market wait as `NEW` (decision 0007). Something has to settle them when the market opens. The first version of that job settled them directly and wrote the result to the database, but published nothing, so the customer was never notified and the analytics load never saw the fill.

## Options considered

| Option | For | Against |
|---|---|---|
| Order-service settles them | Close to where orders are placed | Settlement and publishing already live in the executor, so this would copy them |
| The executor's job settles through the same path as a live order | One place settles and publishes | The job has to avoid racing the Kafka consumer for orders just placed |

## Decision

The executor's `QueuedOrderReleaser` calls `OrderExecutionService.executeQueued`, which settles and publishes to `trade-events` exactly as for a live order. It skips orders under a minute old so the consumer settles those, and each run first retries any outcome that committed but could not be sent. An outcome waiting to be sent is kept in memory, so it survives a Kafka outage but not an executor restart.

## Consequences

- Checked live on 2026-10-07: with Kafka down the order settled and the publish kept failing. After Kafka returned, the next run published it and the customer was notified.
- Losing the executor process while an outcome is unpublished loses that publish. Fixing it needs a stored outbox.
