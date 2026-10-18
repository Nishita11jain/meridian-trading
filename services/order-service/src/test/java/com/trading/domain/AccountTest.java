package com.trading.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    private Account account(String cash, AccountStatus status) {
        return new Account(1L, "REF-1", "Holder", Money.of(cash), status, 3);
    }

    // Account is active
    @Test
    void activeAccountReportsActive() {
        assertTrue(account("100.00", AccountStatus.ACTIVE).isActive());
    }

    // Other account status' not active
    @Test
    void suspendedAccountReportsNotActive() {
        assertFalse(account("100.00", AccountStatus.SUSPENDED).isActive());
    }

    // Cant afford
    @Test
    void canAffordFalseWhenBalanceBelowAmount() {
        assertFalse(account("50.00", AccountStatus.ACTIVE).canAfford(Money.of("100.00")));
    }

    // Successful debit
    @Test
    void debitReducesBalanceWhenAffordable() {
        Account account = account("100.00", AccountStatus.ACTIVE);
        account.debit(Money.of("40.00"));
        assertEquals(Money.of("60.00"), account.getCashBalance());
    }

    // Unsuccessful debit
    @Test
    void debitRefusesToLeaveBalanceNegative() {
        Account account = account("50.00", AccountStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> account.debit(Money.of("50.01")));
        assertEquals(Money.of("50.00"), account.getCashBalance());
    }

    // Successful credit
    @Test
    void creditIncreasesBalance() {
        Account account = account("100.00", AccountStatus.ACTIVE);
        account.credit(Money.of("25.00"));
        assertEquals(Money.of("125.00"), account.getCashBalance());
    }

    // Aggregate balance is valid
    @Test
    void moneyDoesNotDriftOverManyOperations() {
        Account account = account("0.00", AccountStatus.ACTIVE);
        for (int i = 0; i < 1000; i++) {
            account.credit(Money.of("0.10"));
        }
        for (int i = 0; i < 500; i++) {
            account.debit(Money.of("0.10"));
        }
        assertEquals(Money.of("50.00"), account.getCashBalance());
    }
}
