package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;

/** A filled order, in execution order, for the realised-P&L walk. */
public class FilledOrderRow {
    private String symbol;
    private String side;
    private int quantity;
    private BigDecimal executedPrice;
    private java.time.Instant executedOn;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getExecutedPrice() { return executedPrice; }
    public void setExecutedPrice(BigDecimal executedPrice) { this.executedPrice = executedPrice; }
    public java.time.Instant getExecutedOn() { return executedOn; }
    public void setExecutedOn(java.time.Instant executedOn) { this.executedOn = executedOn; }
}
