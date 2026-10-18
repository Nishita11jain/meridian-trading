package com.trading.api.extensions.advice;

import java.time.Instant;

/** The candle_cache row: Fauxnance's own candle response, kept verbatim, plus the cache's own timestamps. */
public class CandleCacheRow {

    private String symbol;
    private String interval;
    private String currency;
    private String candlesJson;
    private Instant fetchedAt;
    private Instant expiresAt;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCandlesJson() {
        return candlesJson;
    }

    public void setCandlesJson(String candlesJson) {
        this.candlesJson = candlesJson;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
