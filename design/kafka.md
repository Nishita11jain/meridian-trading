# Kafka Design

This document covers Sprint 7's Kafka topics, keys, partitioning, dead-letter topics, and local creation command.

## Topics

| Topic | Purpose | Key | Partitions | Retention |
|---|---|---|---|---|
| `orders` | Accepted orders awaiting execution | `accountId` | 3 | 7 days |
| `trade-events` | Filled, rejected, and cancelled lifecycle events | `accountId` | 3 | 30 days |
| `market-data` | Polled quotes | `symbol` | 6 | 1 day |

Each topic also has a dead-letter topic:

- `orders.DLT`
- `trade-events.DLT`
- `market-data.DLT`

## Why these keys

The key decides the partition, and the partition decides the ordering guarantee.

- `orders` and `trade-events` are keyed by `accountId` because order execution and order outcomes must stay ordered per account.
- `market-data` is keyed by `symbol` because quote ordering must stay ordered per instrument.
- Keying by order id would scatter one account's messages across partitions and break the per-account ordering guarantee.

## Why these partition counts

- Three partitions on `orders` and `trade-events` allow three executor instances to share the work while preserving per-account ordering.
- Six partitions on `market-data` matches the higher quote volume.
- Partitions can be increased later but never decreased, and increasing them rehashes keys, so an account's history splits from that point onward.

## How this applies to our deployment

The Trade Executor and the market-data poller are their own service, `executor/` (package
`com.trading.executor`), separate from `trade-api-service`. The API produces `ORDER_PLACED` events and, as of Sprint 10,
also consumes `trade-events` (for notifications) and `market-data` (for watchlist alerts). The executor consumes from `orders` 
and publishes to `trade-events`. This shape drives three decisions.

**How many instances are useful.** `orders` has 3 partitions and one consumer group, and a partition
is read by at most one group member. Up to three executor instances each take a partition; a fourth
instance joins the group, is assigned no partition and idles on `orders`, but it still runs a poller, so
adding it multiplies the Fauxnance spend without adding execution capacity. Run three executor instances
at most. API instances scale separately, for HTTP load, and cost no Fauxnance quota.

**Which consumer notices a partition increase.** Raising the partition count of a topic rehashes its
keys, so an account's later messages can land on a different partition from its earlier ones, and
ordering across that boundary is lost.
- `orders` is the one that matters: the `trade-executor` group would assign an account's new order and
  its still-unprocessed older order to different partitions, and two instances could execute them in
  either order. A sell could then settle before the buy that funds it. Nothing else in the platform
  reads `orders`.
- `trade-events` consumers (Sprint 10 portfolio, notification and strategy services) would see one
  account's events split across partitions and must not assume ordering across the change.
- `market-data` consumers see the same for a symbol: an older quote could arrive after a newer one.
- The poller and the API producer do not care, because they only write.

So partition counts are set once, in Sprint 7, and are not raised while orders are in flight.

**Order publishing and recovery.** `ORDER_PLACED` is published after the order commits. If that
publish fails, the order stays `NEW`, and `OrderPublishReconciler` republishes orders still `NEW`
after two minutes. This is safe because the executor's guarded update
(`WHERE id = ? AND status = 'NEW'`) makes a repeated message a no-op. See
`services/order-service/PUBLISH-FAILURE-RECOVERY.md`.

## Dead-letter topics

- **One partition each.** Dead-lettering is rare, a dead-letter topic has no consumer group in this
  sprint, and nothing needs parallelism or per-account ordering on it: a message lands there because it
  failed, and a person reads it. One partition keeps every failure in one place to inspect, and it can
  be raised later if a replay tool ever needs to read it in parallel.
- **Same retention as the source topic**, so a failure can be inspected for as long as the message
  could have lived.
- **The value is the original message, untouched**, so it can be replayed to its source topic as it
  was. The reason is carried in headers:

| Header | Content |
|---|---|
| `failure-reason` | One of `malformed-json`, `order-not-found`, `unexpected-event-type`, `missing-required-field`, `missing-idempotency-key`, `exhausted-retry-budget`, `trade-event-publish-failed`, `database-error`, `broker-timeout`, `kafka-error`, `unknown-error` |
| `failure-timestamp` | When it was dead-lettered, ISO 8601 |
| `failure-class` | Simple class name of the exception that caused it |

- **Permanent failures are dead-lettered on the first attempt** (malformed JSON, missing order id,
  order not in Postgres, unexpected event type). **Transient ones are retried** with a growing wait,
  up to four attempts, then dead-lettered. An optimistic-lock budget spent inside the executor is
  dead-lettered as `exhausted-retry-budget` without being retried again, so one order cannot cost
  twelve settle attempts.
- **Settled but not published.** If the order was settled and committed but its `trade-events` message
  could not be sent (after three in-place retries), the executor keeps the outcome and the consumer retries
  the message; the retry publishes the kept outcome instead of settling again. If those retries are spent, the
  message is dead-lettered as `trade-event-publish-failed`, which tells an operator that money moved but the
  event did not go out. The kept outcome is in memory, so a restart before the retry loses it.
- **The dead-letter write is awaited.** The original message is acknowledged only after the dead-letter
  topic has confirmed it. If that write fails, the message is not acknowledged and is redelivered.
- A Fauxnance outage is not dead-lettered: the order is rejected with the reason `PRICING_UNAVAILABLE`, so it never
  sits at `NEW`.

## Rejection reasons

The `reason` of a rejected `trade-events` message is a stable token, not free text, so a consumer can
branch on it: `INSTRUMENT_NOT_TRADABLE`, `ACCOUNT_SUSPENDED`, `INSUFFICIENT_FUNDS`,
`INSUFFICIENT_HOLDINGS`, `PRICE_NOT_MET` (a buy below the ask, or a sell above the bid) and
`PRICING_UNAVAILABLE`. A cancelled order carries `CANCELLED_BY_CUSTOMER`. They are defined in
`executor/.../domain/RejectionReason.java`. Orders rejected before this change keep the old free text
in `orders.rejection_reason`.

## Fauxnance quota and the poll interval

The daily quota is 2,000 requests per key, shared by the poller and by every fill the executor
prices, and it is counted per executor instance (each instance has its own counter).

| Item | Value |
|---|---|
| Poller share | 1,500 requests a day |
| Reserved for fills | 500 requests a day |
| Default interval | 60 s, which is 1,440 polls a day for one batch of up to 25 symbols |
| Left for fills at 60 s | 560 |
| Shortest allowed interval | 58 s (1,490 polls); the service refuses to start below it |

The floor is enforced in code (`MarketDataPoller.validateIntervalFloor`), not just written down. An
interval such as 44 s stays under 2,000 requests a day (1,964) but leaves only 36 for fills, so it is
refused. The poller fetches only symbols someone holds, in batches of at most 25. Each extra batch adds
one request per poll, so with more than 25 held symbols the interval alone no longer bounds the spend.
For that case every poll checks the remaining quota and skips itself when it would dip into the fills
reserve. Each HTTP attempt, including a retry, counts as one request.

## Message envelope

Every message carries the same five-field envelope plus `payload`.

- `eventId`
- `eventType`
- `eventTime`
- `source`
- `schemaVersion`
- `payload`

The `orders` payload follows `contracts/event-schemas/kafka-topics.md` exactly: `orderId`, `accountId`, `symbol`, `side`,
`quantity`, `price` (the limit price), `idempotencyKey` and `createdOn` (when the order was recorded). `source`
is `trade-api`, and `eventTime` and `createdOn` are RFC 3339 strings in UTC. A message published before this
was aligned may still carry `limitPrice` and no `createdOn`; the executor accepts `limitPrice` as an alias
and only ever reads `orderId`, because it loads the order itself from Postgres.

Consumers must ignore unknown fields so additive changes do not break existing consumers.
Deserialisation goes through Spring Boot's auto-configured Jackson `ObjectMapper`, which
disables `FAIL_ON_UNKNOWN_PROPERTIES` by default; nothing in this codebase re-enables it, so
an extra field on any of the three topics is silently ignored rather than raising an
error. `com.trading.executor.consumer.OrderPlacedConsumerUnknownFieldsTest` pins this.

## Topic creation command

Run the following script from the repository root after the broker is up:

```bash
bash infrastructure/kafka/scripts/create-topics.sh
```

The script starts the broker container and then creates all six topics against `localhost:9092` by
default. It uses `--if-not-exists`, so it can be re-run. Two environment variables override its
defaults: `KAFKA_BOOTSTRAP_SERVERS` for the bootstrap server, and `PRIVATE_IP` for the address the
broker binds to and advertises (default `127.0.0.1`; set it to your LAN address if other machines must
reach the broker). After it runs, confirm the result rather than assuming it:

```bash
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

## Review notes

- Auto-creation is off on the broker itself: `create_kafka_topics.sh` starts the container with
  `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, so a producer writing to a topic nobody created gets
  an error instead of silently getting a one-partition, default-retention topic.
- `orders` is the only work-queue topic with one logical consumer group.
- The dead-letter topics exist explicitly so poison messages never silently land in auto-created topics.