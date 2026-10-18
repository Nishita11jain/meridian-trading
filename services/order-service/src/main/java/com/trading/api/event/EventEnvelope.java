package com.trading.api.event;

import java.time.Instant;

/**
 * Generic Kafka event envelope, as contracts/event-schemas/kafka-topics.md fixes it. This
 * service's own copy -- the Trade Executor (a separate service/project) has
 * an identical one for the messages it publishes; neither depends on the
 * other's package.
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        Instant eventTime,
        String source,
        int schemaVersion,
        T payload
) {
}
