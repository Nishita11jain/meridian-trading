package com.trading.api.service;

import com.trading.api.dto.AccountResponse;
import com.trading.api.dto.BalanceResponse;
import com.trading.api.dto.PositionResponse;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Money;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private PositionMapper positionMapper;

    private AccountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountServiceImpl(accountMapper, positionMapper);
    }

    @Test
    void getAccountMapsNumericAndBusinessIdentifiersCorrectly() {
        AccountRow account = account("1000.00", AccountStatus.ACTIVE, 4);
        Instant lastUpdated = Instant.parse("2026-09-08T09:00:00Z");
        when(accountMapper.findById(1L)).thenReturn(account);
        when(accountMapper.findLastUpdated(1L)).thenReturn(lastUpdated);

        AccountResponse response = service.getAccount(1L, 1L);

        assertEquals(1L, response.id());
        assertEquals("ACC-REF-1", response.accountId());
        assertEquals("Holder", response.holderName());
        assertEquals(new BigDecimal("1000.00"), response.cashBalance());
        assertEquals(AccountStatus.ACTIVE, response.status());
        assertEquals(4, response.version());
        assertEquals(lastUpdated, response.lastUpdated());
    }

    @Test
    void getAccountRejectsATokenForADifferentAccount() {

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getAccount(1L, 2L));

        assertEquals("ACC-403", exception.code());
        // Same message a genuinely suspended account gets -- deliberately,
        // so a caller can't tell "wrong token" apart from "not active".
        assertEquals("account is not active", exception.getMessage());
    }

    @Test
    void getAccountRejectsSuspendedAccount() {
        when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.SUSPENDED, 4));

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getAccount(1L, 1L));

        assertEquals("ACC-403", exception.code());
        assertEquals("account is not active", exception.getMessage());
    }

    @Test
    void getBalanceUsesAccountStateAndFixedCurrency() {
        when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));

        BalanceResponse response = service.getBalance(1L, 1L);

        assertEquals(1L, response.accountId());
        assertEquals(new BigDecimal("1000.00"), response.cashBalance());
        assertEquals("INR", response.currency());
        assertNotNull(response.asOf());
    }

    @Test
    void getBalanceRejectsATokenForADifferentAccount() {

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getBalance(1L, 2L));

        assertEquals("ACC-403", exception.code());
    }

    @Test
    void anAccountThatIsNotTheCallersOwnIsRefusedWithoutBeingLookedUp() {
        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getBalance(999L, 1L));

        assertEquals("ACC-403", exception.code());
        org.mockito.Mockito.verify(accountMapper, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getPositionsRejectsUnknownAccount() {
        when(accountMapper.findById(1L)).thenReturn(null);

        AccountNotFoundException exception = assertThrows(AccountNotFoundException.class,
                () -> service.getPositions(1L, 1L));

        assertEquals("ACC-404", exception.code());
    }

    @Test
    void getPositionsRejectsATokenForADifferentAccount() {

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getPositions(1L, 2L));

        assertEquals("ACC-403", exception.code());
    }

    @Test
    void getPositionsMapsHeldPositionsToResponseDtos() {
        when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
        when(positionMapper.findAllHeldByAccount(1L)).thenReturn(List.of(
                position(1L, "INFY.NS", 10, "100.00"),
                position(1L, "TCS.NS", 5, "250.50")));

        List<PositionResponse> response = service.getPositions(1L, 1L);

        assertEquals(2, response.size());
        assertEquals(new PositionResponse(1L, "INFY.NS", 10, new BigDecimal("100.00")), response.get(0));
        assertEquals(new PositionResponse(1L, "TCS.NS", 5, new BigDecimal("250.50")), response.get(1));
        verify(positionMapper).findAllHeldByAccount(1L);
    }

    private static AccountRow account(String cash, AccountStatus status, int version) {
        AccountRow account = new AccountRow();
        account.setId(1L);
        account.setAccountId("ACC-REF-1");
        account.setHolderName("Holder");
        account.setCashBalance(Money.of(cash));
        account.setStatus(status);
        account.setVersion(version);
        return account;
    }

    private static PositionRow position(Long accountId, String symbol, int quantity, String averageCost) {
        PositionRow position = new PositionRow();
        position.setAccountId(accountId);
        position.setSymbol(symbol);
        position.setQuantity(quantity);
        position.setAverageCost(Money.of(averageCost));
        position.setVersion(0);
        return position;
    }
}