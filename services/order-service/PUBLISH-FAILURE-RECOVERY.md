# When an order is saved but never published

## The issue

`POST /api/v1/orders` saves the order as `NEW`, commits, and only then publishes
`ORDER_PLACED` to Kafka. That order is deliberate (see `contracts/event-schemas/kafka-topics.md`):
an event for an order that rolled back cannot be undone, but an order that
committed and was never published can be replayed.

The gap was the replay. If the publish failed after the commit:

- the order stayed `NEW` in the database, and no money had moved;
- the customer had already been told "Order accepted", or got a 500 if the broker
  was slow to time out;
- nothing ever published the order again, so the executor never saw it and it
  stayed `NEW` forever.

## What we did

| Change | Where |
|---|---|
| The publish now waits for the broker's acknowledgement (5 s) and throws `OrderPublishException` on failure, instead of fire-and-forget. | `KafkaOrderEventPublisher` |
| A publish failure after commit is logged at error level and does not fail the request. The order is saved, so the client still gets `NEW`. | `OrderServiceImpl.runPublication` |
| `send()` no longer blocks for a minute on an unreachable broker (`max.block.ms` = 5 s). | `KafkaProducerConfig` |
| A scheduled reconciler finds orders still `NEW` after 2 minutes and publishes `ORDER_PLACED` for them again. | `OrderPublishReconciler`, `OrderMapper.findStaleNewOrders` |

### How the reconciler behaves

- Runs every 60 s, oldest orders first, at most 100 per run.
- Stops the run at the first failed publish. The broker is probably down, and every
  further attempt would wait out its timeout.
- Does not replay the same order again within 5 minutes, so a long outage does not
  become a flood of duplicates when Kafka returns.
- Logs an error for any order that has been `NEW` for more than 10 minutes.
- Settings (all optional): `reconciler.interval-seconds`, `reconciler.min-age-seconds`,
  `reconciler.replay-cooldown-seconds`, `reconciler.alert-age-seconds`, `reconciler.batch-size`.

### Why replaying is safe

The executor only settles an order that is still `NEW`
(`UPDATE ... WHERE id = ? AND status = 'NEW'`). A duplicate `ORDER_PLACED` finds the
order already settled, changes nothing and publishes nothing, so a replay can never
debit an account twice. If several API replicas run the reconciler at once, the
worst case is a few harmless duplicates.

### What happens if Kafka is down for a long time

The order stays `NEW`. The reconciler keeps trying, and logs an error once an order
has been `NEW` for over 10 minutes. When Kafka returns, the next run publishes it,
the executor fills or rejects it, and the order leaves `NEW`.

## What we can do for better

1. **Transactional outbox.** Insert an `outbox` row in the same transaction as the
   order, and have a relay publish it and mark it sent. The order and its "to
   publish" record then commit together, nothing can be missed, and there is no age
   threshold to guess. It costs a new table, a migration and changes to the insert
   path, so we chose the reconciler for this sprint. The contract itself says to
   recover "by replaying from the order table".
2. **Expire stuck orders.** After a long limit, such as an hour, mark a still-`NEW`
   order `REJECTED` with a reason like "not executed", using
   `WHERE status = 'NEW'` so a fill cannot be overwritten. This is a business
   decision, so it is not done.
3. **One reconciler at a time.** Use a database lock or a lease so only one replica
   replays. That removes the harmless duplicates.
4. **Metrics and alerts.** Export the count and age of `NEW` orders so an outage
   pages someone, instead of relying on log lines.
5. **Persist the executor's unpublished fills.** The executor keeps a committed fill
   that failed to reach `trade-events` in memory, so a restart loses it. An outbox
   on that side closes the same gap for `ORDER_FILLED` and `ORDER_REJECTED`.
