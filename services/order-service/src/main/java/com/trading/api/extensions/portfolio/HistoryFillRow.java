package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** A filled order as the history walk needs it: what, which way, how many, at what price, when, in which currency. */
public class HistoryFillRow {

    private String symbol;
    private String side;
    private long quantity;
    private BigDecimal executedPrice;
    private Instant executedOn;
    private String currency;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }
    public BigDecimal getExecutedPrice() { return executedPrice; }
    public void setExecutedPrice(BigDecimal executedPrice) { this.executedPrice = executedPrice; }
    public Instant getExecutedOn() { return executedOn; }
    public void setExecutedOn(Instant executedOn) { this.executedOn = executedOn; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
