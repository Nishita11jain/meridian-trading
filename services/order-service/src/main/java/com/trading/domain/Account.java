package com.trading.domain;

import java.util.Objects;


public class Account {

    private final Long accountId;
    private final String accountReference;
    private final String holderName;
    private Money cashBalance;
    private final AccountStatus status;
    private final int version;

    public Account(Long accountId, String accountReference, String holderName,
                    Money cashBalance, AccountStatus status, int version) {
        this.accountId = accountId;
        this.accountReference = accountReference;
        this.holderName = holderName;
        this.cashBalance = cashBalance;
        this.status = status;
        this.version = version;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public String getHolderName() {
        return holderName;
    }

    public Money getCashBalance() {
        return cashBalance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean canAfford(Money amount) {
        return cashBalance.isGreaterThanOrEqualTo(amount);
    }

    public void debit(Money amount) {
        if (!canAfford(amount)) {
            throw new IllegalStateException("Debit of " + amount + " would leave account " + accountId + " negative");
        }
        cashBalance = cashBalance.subtract(amount);
    }

    public void credit(Money amount) {
        cashBalance = cashBalance.add(amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return Objects.equals(accountId, other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }
}
