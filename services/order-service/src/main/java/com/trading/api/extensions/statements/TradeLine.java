package com.trading.api.extensions.statements;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One filled trade, as a statement lists it: what was bought or sold, at what price, and when. */
public class TradeLine {

    private LocalDateTime executedAt;
    private String orderId;
    private String symbol;
    private String side;
    private long quantity;
    private BigDecimal price;
    private String currency;

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    /** The trade's value in its own currency: quantity times price. */
    public BigDecimal getValue() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
