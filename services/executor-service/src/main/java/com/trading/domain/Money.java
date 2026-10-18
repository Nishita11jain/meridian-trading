package com.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money is decimal at two places, never a double, so no operation on it can drift.
 */
public final class Money {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value).setScale(2, RoundingMode.HALF_UP));
    }

    public static Money of(BigDecimal value) {
        return new Money(value.setScale(2, RoundingMode.HALF_UP));
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(long units) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(units)));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return this.amount.compareTo(other.amount) >= 0;
    }

    public BigDecimal asBigDecimal() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
