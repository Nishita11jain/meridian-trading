package com.trading.api.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.domain.Money;
import com.trading.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays ORDER_PLACED for orders that were committed at NEW but never
 * reached the executor, because the publish after commit failed.
 *
 * The order table is the source of truth: an order still NEW after a couple
 * of minutes was accepted and never executed. Replaying it is safe, since the
 * executor settles only an order that is still NEW (WHERE status = 'NEW'), so
 * a message delivered twice moves no money twice.
 *
 * Limits, on purpose:
 * - a batch is capped, oldest first;
 * - the first failed publish ends the run, because the broker is probably
 *   down and every further attempt would wait out its timeout;
 * - an order is not replayed again within the cool-down, so a long outage
 *   does not turn into a flood of duplicates when the broker returns;
 * - an order NEW for longer than the alert age is logged at error level.
 */
@Component
public class OrderPublishReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrderPublishReconciler.class);

    private final OrderMapper orderMapper;
    private final TradingHours tradingHours;
    private final OrderEventPublisher orderEventPublisher;
    private final Duration minAge;
    private final Duration replayCooldown;
    private final Duration alertAge;
    private final int batchSize;

    private final Map<UUID, Instant> lastReplayed = new ConcurrentHashMap<>();

    public OrderPublishReconciler(
            OrderMapper orderMapper,
            OrderEventPublisher orderEventPublisher,
            TradingHours tradingHours,
            @Value("${reconciler.min-age-seconds:120}") long minAgeSeconds,
            @Value("${reconciler.replay-cooldown-seconds:300}") long replayCooldownSeconds,
            @Value("${reconciler.alert-age-seconds:600}") long alertAgeSeconds,
            @Value("${reconciler.batch-size:100}") int batchSize) {

        this.orderMapper = orderMapper;
        this.tradingHours = tradingHours;
        this.orderEventPublisher = orderEventPublisher;
        this.minAge = Duration.ofSeconds(minAgeSeconds);
        this.replayCooldown = Duration.ofSeconds(replayCooldownSeconds);
        this.alertAge = Duration.ofSeconds(alertAgeSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${reconciler.interval-seconds:60}000")
    public void replayUnpublishedOrders() {

        Instant now = Instant.now();

        List<OrderExecutionRow> stale;
        try {
            stale = orderMapper.findStaleNewOrders(now.minus(minAge), batchSize);
        } catch (RuntimeException exception) {
            log.error("Could not look for unpublished orders", exception);
            return;
        }

        // Forget orders that have left NEW, so the map cannot grow without bound.
        lastReplayed.keySet().removeIf(id -> stale.stream().noneMatch(row -> id.equals(row.getId())));

        int replayed = 0;

        for (OrderExecutionRow row : stale) {

            // A queued order (its market is closed) is waiting on purpose, not lost: replaying it would only
            // re-send the same event, and alerting on it would be noise. It is handled once the market opens.
            if (!tradingHours.status(row.getSymbol()).open()) {
                continue;
            }

            if (row.getCreatedOn() != null
                    && Duration.between(row.getCreatedOn(), now).compareTo(alertAge) > 0) {
                log.error(
                        "Order {} has been NEW since {} and has not been executed",
                        row.getId(), row.getCreatedOn()
                );
            }

            Instant previous = lastReplayed.get(row.getId());
            if (previous != null && Duration.between(previous, now).compareTo(replayCooldown) < 0) {
                continue;
            }

            try {
                orderEventPublisher.publishOrderPlaced(toOrder(row));
                lastReplayed.put(row.getId(), now);
                replayed++;
            } catch (RuntimeException exception) {
                log.warn(
                        "Replay of order {} failed; stopping this run, will retry next run: {}",
                        row.getId(), exception.getMessage()
                );
                break;
            }
        }

        if (replayed > 0) {
            log.info("Replayed ORDER_PLACED for {} order(s) still NEW", replayed);
        }
    }

    private static Order toOrder(OrderExecutionRow row) {
        return new Order(
                row.getId(),
                row.getAccountId(),
                row.getSymbol(),
                row.getSide(),
                row.getQuantity(),
                Money.of(row.getLimitPrice()),
                row.getIdempotencyKey(),
                row.getCreatedOn()
        );
    }
}
