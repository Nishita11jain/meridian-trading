package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** A held position joined with its instrument currency and latest quote (price/asOf/stale may be null). */
public class PricedPositionRow {
    private Long accountId;
    private String symbol;
    private int quantity;
    private BigDecimal averageCost;
    private String currency;
    private BigDecimal lastPrice;
    private Instant quoteAsOf;
    private Boolean stale;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getAverageCost() { return averageCost; }
    public void setAverageCost(BigDecimal averageCost) { this.averageCost = averageCost; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public void setLastPrice(BigDecimal lastPrice) { this.lastPrice = lastPrice; }
    public Instant getQuoteAsOf() { return quoteAsOf; }
    public void setQuoteAsOf(Instant quoteAsOf) { this.quoteAsOf = quoteAsOf; }
    public Boolean getStale() { return stale; }
    public void setStale(Boolean stale) { this.stale = stale; }
}
