package com.trading.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class Order {

    private final UUID id;
    private final Long accountId;
    private final String symbol;
    private final OrderSide side;
    private final long quantity;
    private final Money limitPrice;
    private Money executedPrice;
    private OrderStatus status;
    private final String idempotencyKey;
    private final Instant createdOn;

    public Order(UUID id, Long accountId, String symbol, OrderSide side, long quantity,
                 Money limitPrice, String idempotencyKey, Instant createdOn) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.side = Objects.requireNonNull(side, "side must not be null");
        this.quantity = quantity;
        this.limitPrice = Objects.requireNonNull(limitPrice, "limitPrice must not be null");
        this.executedPrice = null;
        this.status = OrderStatus.NEW;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.createdOn = Objects.requireNonNull(createdOn, "createdOn must not be null");
    }

    public UUID getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public long getQuantity() {
        return quantity;
    }

    public Money getLimitPrice() {
        return limitPrice;
    }

    public Money getExecutedPrice() {
        return executedPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedOn() {
        return createdOn;
    }

    public boolean isTerminal() {
        return status == OrderStatus.FILLED || status == OrderStatus.REJECTED || status == OrderStatus.CANCELLED;
    }

    public void fill(Money executedPrice) {
        refuseIfTerminal();
        this.executedPrice = Objects.requireNonNull(executedPrice, "executedPrice must not be null");
        this.status = OrderStatus.FILLED;
    }

    public void reject() {
        refuseIfTerminal();
        this.status = OrderStatus.REJECTED;
    }

    public void cancel() {
        refuseIfTerminal();
        this.status = OrderStatus.CANCELLED;
    }

    private void refuseIfTerminal() {
        if (isTerminal()) {
            throw new IllegalStateException("order " + id + " is already terminal (" + status + ")");
        }
    }
}
