package com.trading.executor.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.row.OrderExecutionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Settles queued orders when their own market opens (decision log 0019).
 *
 * <p>An order placed while its market is closed stays NEW, with its cash or shares reserved. The consumer
 * holds it rather than rejecting it. This job runs on a fixed delay, finds the NEW orders oldest first, and
 * settles each one whose market is open at that moment. Each settlement goes through the same
 * path as a live order, {@link OrderExecutionService}, so the outcome is published to trade-events too. Its
 * status guard means an order that the consumer has already settled is left alone.
 *
 * <p>The database is the queue, so a restart loses nothing and a cancelled order simply stops being NEW.
 */
@Component
public class QueuedOrderReleaser {

    private static final Logger log = LoggerFactory.getLogger(QueuedOrderReleaser.class);

    /** How many NEW orders one run looks at. Closed-market orders are skipped, not counted against open ones. */
    static final int BATCH_SIZE = 500;

    /**
     * A NEW order this young is probably still on its way through the orders topic, where the consumer
     * will settle it. Leaving it alone for a minute keeps the two paths from racing for the same order.
     */
    static final Duration MIN_AGE = Duration.ofMinutes(1);

    private final OrderMapper orderMapper;
    private final MarketHours marketHours;
    private final OrderExecutionService executionService;

    public QueuedOrderReleaser(OrderMapper orderMapper, MarketHours marketHours,
                               OrderExecutionService executionService) {
        this.orderMapper = orderMapper;
        this.marketHours = marketHours;
        this.executionService = executionService;
    }

    @Scheduled(fixedDelayString = "${queue.release-interval-seconds:30}000")
    public void releaseOpenMarkets() {
        executionService.republishUnpublished();

        List<OrderExecutionRow> queued;
        try {
            queued = orderMapper.findNewOrdersOldestFirst(BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.error("Could not read queued orders; will retry on the next run", exception);
            return;
        }

        int released = 0;
        for (OrderExecutionRow order : queued) {
            if (!marketHours.isOpen(order.getSymbol())) {
                continue;
            }
            if (order.getCreatedOn() != null && order.getCreatedOn().isAfter(Instant.now().minus(MIN_AGE))) {
                continue;
            }
            try {
                executionService.executeQueued(order.getId());
                released++;
            } catch (RuntimeException exception) {
                // One bad order must not hold up the rest of the queue. It stays NEW and is tried again.
                log.warn("Could not release queued order {}; will retry: {}", order.getId(), exception.getMessage());
            }
        }

        if (released > 0) {
            log.info("Released {} queued order(s) whose market is now open", released);
        }
    }
}
