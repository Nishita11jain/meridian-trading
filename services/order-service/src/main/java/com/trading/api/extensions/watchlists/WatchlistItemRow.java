package com.trading.api.extensions.watchlists;

/** One watchlist item as stored: the instrument and its colour flag (null for none). */
public class WatchlistItemRow {

    private String symbol;
    private String flag;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }
}
