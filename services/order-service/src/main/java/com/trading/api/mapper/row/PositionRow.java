package com.trading.api.mapper.row;

import com.trading.domain.Money;

public class PositionRow {

    private Long accountId;
    private String symbol;
    private Integer quantity;
    private Money averageCost;
    private Integer version;

    public PositionRow() {
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Money getAverageCost() {
        return averageCost;
    }

    public void setAverageCost(Money averageCost) {
        this.averageCost = averageCost;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}