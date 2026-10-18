package com.trading.api.extensions.watchlists;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of market_price, the latest quote per symbol. */
public class MarketPriceRow {
    private String symbol;
    private BigDecimal lastPrice;
    private BigDecimal bid;
    private BigDecimal ask;
    private String currency;
    private BigDecimal change;
    private BigDecimal changePercent;
    private BigDecimal previousClose;
    private String marketState;
    private boolean stale;
    private Instant quoteAsOf;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public void setLastPrice(BigDecimal lastPrice) { this.lastPrice = lastPrice; }
    public BigDecimal getBid() { return bid; }
    public void setBid(BigDecimal bid) { this.bid = bid; }
    public BigDecimal getAsk() { return ask; }
    public void setAsk(BigDecimal ask) { this.ask = ask; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getChange() { return change; }
    public void setChange(BigDecimal change) { this.change = change; }
    public BigDecimal getChangePercent() { return changePercent; }
    public void setChangePercent(BigDecimal changePercent) { this.changePercent = changePercent; }
    public BigDecimal getPreviousClose() { return previousClose; }
    public void setPreviousClose(BigDecimal previousClose) { this.previousClose = previousClose; }
    public String getMarketState() { return marketState; }
    public void setMarketState(String marketState) { this.marketState = marketState; }
    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }
    public Instant getQuoteAsOf() { return quoteAsOf; }
    public void setQuoteAsOf(Instant quoteAsOf) { this.quoteAsOf = quoteAsOf; }
}
