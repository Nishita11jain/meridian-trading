package com.trading.api.mapper.row;

import com.trading.domain.AccountStatus;
import com.trading.domain.Money;

public class AccountRow {

    private Long id;
    private String accountId;
    private String holderName;
    private Money cashBalance;
    private AccountStatus status;
    private Integer version;

    public AccountRow() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getHolderName() {
        return holderName;
    }

    public void setHolderName(String holderName) {
        this.holderName = holderName;
    }

    public Money getCashBalance() {
        return cashBalance;
    }

    public void setCashBalance(Money cashBalance) {
        this.cashBalance = cashBalance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}