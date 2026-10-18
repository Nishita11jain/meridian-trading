package com.trading.api.mapper.row;

import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Plain mutable row, mapped by MyBatis via <result>, not <constructor> --
 * the same fix already applied to AccountRow. Reflection-based constructor
 * lookup boxes primitives internally when a typeHandler is also present in
 * the same block, which fails against a record whose real constructor
 * takes a primitive int. Property-based mapping avoids that path
 * entirely; converting into the real OrderHistoryEntry record happens in
 * plain Java code afterward, where int/Integer unboxing is never an issue.
 */
public class OrderHistoryRow {
    private String orderId;
    private Long accountId;
    private String symbol;
    private OrderSide side;
    private int quantity;
    private BigDecimal price;
    private BigDecimal executedPrice;
    private OrderStatus status;
    private String idempotencyKey;
    private Instant createdOn;
    private String rejectionReason;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public OrderSide getSide() { return side; }
    public void setSide(OrderSide side) { this.side = side; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getExecutedPrice() { return executedPrice; }
    public void setExecutedPrice(BigDecimal executedPrice) { this.executedPrice = executedPrice; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }

    /** Why the order was rejected, or null for an order that was not. */
    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}
