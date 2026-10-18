package com.trading.executor.service;

/**
 * Publishes a settled order's outcome to trade-events. Each call sends the
 * full contract payload carried by the outcome, and blocks until the broker
 * has acknowledged it, throwing if it cannot.
 */
public interface OrderRepository {

    void markFilled(SettlementOutcome outcome);

    void markRejected(SettlementOutcome outcome);
}
