package com.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;


public class Position {

    private final Long accountId;
    private final String symbol;
    private int quantity;
    private Money averageCost;
    private final int version;

    public Position(Long accountId, String symbol, int quantity, Money averageCost, int version) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageCost = averageCost;
        this.version = version;
    }

    public static Position openNew(Long accountId, String symbol) {
        return new Position(accountId, symbol, 0, Money.ZERO, 0);
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getQuantity() {
        return quantity;
    }

    public Money getAverageCost() {
        return averageCost;
    }

    public int getVersion() {
        return version;
    }

    public boolean hasSufficientHoldings(int units) {
        return quantity >= units;
    }

    public void applyBuy(int units, Money price) {
        Money totalCost = averageCost.multiply(quantity).add(price.multiply(units));
        int newQuantity = quantity + units;
        averageCost = Money.of(totalCost.asBigDecimal()
                .divide(BigDecimal.valueOf(newQuantity), 2, RoundingMode.HALF_UP));
        quantity = newQuantity;
    }

    public void applySell(int units) {
        if (!hasSufficientHoldings(units)) {
            throw new IllegalStateException(
                    "Cannot sell " + units + " units of " + symbol + "; only " + quantity + " held");
        }
        quantity -= units;
    }
}
