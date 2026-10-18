package com.trading.api.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trading.domain.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedEvent(
        String eventId,
        String eventType,
        Instant eventTime,
        String source,
        int schemaVersion,
        Payload payload
) {

    /**
     * ORDER_PLACED payload as fixed by contracts/event-schemas/kafka-topics.md. The limit
     * price is {@code price} on the wire; the alias keeps messages already on
     * the topic under the old {@code limitPrice} name readable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            UUID orderId,
            Long accountId,
            String symbol,
            OrderSide side,
            long quantity,
            @JsonAlias("limitPrice") BigDecimal price,
            String idempotencyKey,
            Instant createdOn
    ) {

        public Payload(
                UUID orderId,
                Long accountId,
                String symbol,
                OrderSide side,
                long quantity,
                BigDecimal price,
                String idempotencyKey) {

            this(orderId, accountId, symbol, side, quantity, price, idempotencyKey, null);
        }
    }
}
