package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** A price the platform stored for a symbol (price_history), and when it stored it. */
public class PriceTickRow {

    private String symbol;
    private BigDecimal price;
    private Instant recordedOn;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Instant getRecordedOn() { return recordedOn; }
    public void setRecordedOn(Instant recordedOn) { this.recordedOn = recordedOn; }
}
