package com.trading.domain;

/**
 * Delisting flips a flag rather than deleting the row -- order history still
 * references the symbol, and that history is the audit trail.
 */
public class Instrument {

    private final String symbol;
    private final String displayName;
    private final String assetClass;
    private final String currency;
    private boolean tradable;

    public Instrument(String symbol, String displayName, String assetClass, String currency, boolean tradable) {
        this.symbol = symbol;
        this.displayName = displayName;
        this.assetClass = assetClass;
        this.currency = currency;
        this.tradable = tradable;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isTradable() {
        return tradable;
    }

    public void delist() {
        tradable = false;
    }

    public void relist() {
        tradable = true;
    }
}
