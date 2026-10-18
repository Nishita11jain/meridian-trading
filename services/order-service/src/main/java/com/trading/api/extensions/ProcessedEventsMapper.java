package com.trading.api.extensions;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * The shared idempotency ledger for the Sprint 10 consumers. Each consumer claims an event exactly
 * once; a redelivery (Kafka is at-least-once) finds the row already there and is a no-op. Shared by
 * the notification consumer and, later, the watchlist consumer, each under its own consumer name so
 * one consumer reprocessing an event does not stop another from processing the same event.
 */
@Mapper
public interface ProcessedEventsMapper {

    /**
     * Records that {@code consumerName} has handled {@code eventId}. Returns 1 when this call claimed
     * the event (it had not been seen), 0 when another delivery already claimed it (a duplicate).
     */
    int claim(@Param("consumerName") String consumerName, @Param("eventId") UUID eventId);
}
