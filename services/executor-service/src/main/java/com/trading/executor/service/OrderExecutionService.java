package com.trading.executor.service;

import com.trading.api.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long LOCK_BACKOFF_MS = 25;

    private static final int MAX_PUBLISH_ATTEMPTS = 3;
    private static final long PUBLISH_BACKOFF_MS = 100;

    private final OrderSettlementService settlementService;
    private final OrderRepository orderRepository;

    /**
     * Fills and rejections that committed but could not be published to
     * trade-events. Once settle() has committed, a redelivery of the same
     * message sees a settled order and returns "not settled", so without this
     * the outcome would be acknowledged and never published. The next delivery
     * publishes it from here instead of settling again.
     *
     * Held in memory: it covers the consumer's own retries and any redelivery
     * to this instance, not a restart. Surviving a restart needs a persisted
     * outbox.
     */
    private final Map<UUID, SettlementOutcome> unpublished = new ConcurrentHashMap<>();

    public OrderExecutionService(
            OrderSettlementService settlementService,
            OrderRepository orderRepository) {

        this.settlementService = settlementService;
        this.orderRepository = orderRepository;
    }

    /**
     * Execute order settlement with built-in retry on optimistic lock failures.
     * Retries up to MAX_ATTEMPTS times on OptimisticLockRetryException, waiting
     * a little longer each time. After a successful settlement, publishes the
     * outcome to trade-events, retrying the publish on its own so a broker
     * timeout can never cause the order to be settled twice.
     *
     * @param order the ORDER_PLACED payload
     * @return the SettlementOutcome indicating whether settlement occurred
     * @throws OrderSettlementRetryExhaustedException if retry budget exhausted on lock failures
     * @throws TradeEventPublishException if the publish still fails after its retries; the
     *         outcome is kept and published on the next delivery
     */
    public SettlementOutcome execute(OrderPlacedEvent.Payload order) {
        return settleAndPublish(order.orderId());
    }

    /**
     * Settles an order that was queued while its market was closed, and publishes the outcome like a live
     * order. Without this the queued-order job settled through {@link OrderSettlementService} directly, the
     * database moved, and nothing reached trade-events, so the customer was never notified.
     */
    public SettlementOutcome executeQueued(UUID orderId) {
        return settleAndPublish(orderId);
    }

    /**
     * Tries again to publish outcomes that committed but could not be sent. A queued order is only seen once
     * as NEW, so this is what gives its outcome another chance. Returns how many went out.
     */
    public int republishUnpublished() {
        int sent = 0;
        for (Map.Entry<UUID, SettlementOutcome> entry : unpublished.entrySet()) {
            try {
                publishWithRetry(entry.getValue());
                unpublished.remove(entry.getKey());
                sent++;
            } catch (RuntimeException stillFailing) {
                log.warn("Outcome of order {} is still unpublished: {}", entry.getKey(), stillFailing.getMessage());
            }
        }
        return sent;
    }

    private SettlementOutcome settleAndPublish(UUID orderId) {

        SettlementOutcome pending = unpublished.get(orderId);
        if (pending != null) {
            publishWithRetry(pending);
            unpublished.remove(orderId);
            return pending;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {

                SettlementOutcome outcome =
                        settlementService.settle(orderId);

                if (outcome.settled()) {
                    unpublished.put(orderId, outcome);
                    publishWithRetry(outcome);
                    unpublished.remove(orderId);
                }
                return outcome;

            } catch (OptimisticLockRetryException exception) {

                if (attempt == MAX_ATTEMPTS) {
                    throw new OrderSettlementRetryExhaustedException(
                            "exhausted optimistic-lock retry budget for order " + orderId,
                            exception
                    );
                }

                pause(LOCK_BACKOFF_MS * attempt);
            }
        }

        // Should not reach here (all code paths return or throw)
        throw new IllegalStateException("Unexpected: exhausted retry loop without returning");
    }

    private void publishWithRetry(SettlementOutcome outcome) {

        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            try {
                publish(outcome);
                return;
            } catch (RuntimeException exception) {
                if (attempt == MAX_PUBLISH_ATTEMPTS) {
                    throw new TradeEventPublishException(
                            "trade-events publish failed for order " + outcome.orderId()
                                    + " after " + MAX_PUBLISH_ATTEMPTS + " attempts: " + exception.getMessage(),
                            exception
                    );
                }
                log.warn(
                        "Publish of settled order {} failed (attempt {}/{}), retrying: {}",
                        outcome.orderId(), attempt, MAX_PUBLISH_ATTEMPTS, exception.getMessage()
                );
                pause(PUBLISH_BACKOFF_MS * attempt);
            }
        }
    }

    private void publish(SettlementOutcome outcome) {

        if (outcome.filled()) {
            orderRepository.markFilled(outcome);
            return;
        }

        orderRepository.markRejected(outcome);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", interrupted);
        }
    }
}
