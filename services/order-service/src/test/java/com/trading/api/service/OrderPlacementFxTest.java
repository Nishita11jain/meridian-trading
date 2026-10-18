package com.trading.api.service;

import com.trading.api.fx.FxRateUnavailableException;
import com.trading.api.fx.OrderFxService;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.service.OrderEventPublisher;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.PlaceOrderRequest;
import com.trading.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A dollar order is converted once, at placement: the funds check and the reservation are in rupees at the rate
 * locked on the order, and that rate is stored with it. A rupee order is untouched.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementFxTest {

    private static final BigDecimal BUY_RATE = new BigDecimal("83.500000");

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private InstrumentMapper instrumentMapper;
    @Mock
    private PositionMapper positionMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderEventPublisher orderEventPublisher;
    @Mock
    private RejectedOrderRecorder rejectedOrderRecorder;
    @Mock
    private OrderFxService fxService;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(accountMapper, instrumentMapper, positionMapper, orderMapper,
                new OrderPlacementService(key -> false), orderEventPublisher, rejectedOrderRecorder,
                org.mockito.Mockito.mock(TradingHours.class), fxService);
    }

    private static PlaceOrderRequest buy(long quantity, String price, String key) {
        return new PlaceOrderRequest(1L, "MSFT", OrderSide.BUY, (int) quantity, new BigDecimal(price), key);
    }

    private static AccountRow account(String cash) {
        AccountRow row = new AccountRow();
        row.setId(1L);
        row.setAccountId("ACC-REF-1");
        row.setHolderName("Holder");
        row.setCashBalance(Money.of(cash));
        row.setStatus(AccountStatus.ACTIVE);
        row.setVersion(4);
        return row;
    }

    private void givenAccountAndInstrument(String cash, String currency) {
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account(cash));
        when(instrumentMapper.findBySymbol("MSFT")).thenReturn(new Instrument("MSFT", "Microsoft", "EQUITY", currency, true));
        when(positionMapper.findByAccountAndSymbol(1L, "MSFT")).thenReturn(null);
    }

    @Test
    void aDollarBuyIsPlacedAtTheLockedRateAndTheRateIsStoredOnTheOrder() {
        givenAccountAndInstrument("100000.00", "USD");
        when(fxService.lockedRate("USD", OrderSide.BUY)).thenReturn(BUY_RATE);

        service.placeOrder(buy(10, "100.00", "fx-ok-1"), 1L);   // 1,000 dollars = 83,500 rupees, affordable

        verify(orderMapper).insert(any(UUID.class), eq(1L), eq("MSFT"), eq(OrderSide.BUY), eq(10L), eq(null),
                eq(new BigDecimal("100.00")), any(), eq("fx-ok-1"), any(Instant.class));
        verify(orderMapper).updateFxRate(any(UUID.class), eq(BUY_RATE));
    }

    @Test
    void aDollarBuyThatFitsInDollarsButNotInRupeesIsRefusedAsInsufficientFunds() {
        // 1,000 dollars is far below 50,000, but at 83.5 it is 83,500 rupees.
        givenAccountAndInstrument("50000.00", "USD");
        when(fxService.lockedRate("USD", OrderSide.BUY)).thenReturn(BUY_RATE);

        assertThrows(InsufficientFundsException.class, () -> service.placeOrder(buy(10, "100.00", "fx-short-1"), 1L));

        verify(rejectedOrderRecorder).recordRejection(any(), eq(RejectedOrderRecorder.INSUFFICIENT_FUNDS));
        verify(orderMapper, never()).insert(any(), anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any());
        verify(orderMapper, never()).updateFxRate(any(), any());
    }

    @Test
    void cashAlreadyReservedByQueuedOrdersCountsAgainstTheNewDollarOrder() {
        // 100,000 in cash, 40,000 already reserved (in rupees) leaves 60,000, which cannot cover 83,500.
        givenAccountAndInstrument("100000.00", "USD");
        when(fxService.lockedRate("USD", OrderSide.BUY)).thenReturn(BUY_RATE);
        when(orderMapper.sumReservedCash(1L)).thenReturn(new BigDecimal("40000.00"));

        assertThrows(InsufficientFundsException.class, () -> service.placeOrder(buy(10, "100.00", "fx-reserved-1"), 1L));
    }

    @Test
    void aRupeeOrderCarriesNoRateAndIsPlacedAsBefore() {
        givenAccountAndInstrument("100000.00", "INR");
        when(fxService.lockedRate("INR", OrderSide.BUY)).thenReturn(null);

        service.placeOrder(buy(10, "100.00", "fx-inr-1"), 1L);

        verify(orderMapper).insert(any(UUID.class), eq(1L), eq("MSFT"), eq(OrderSide.BUY), eq(10L), eq(null),
                eq(new BigDecimal("100.00")), any(), eq("fx-inr-1"), any(Instant.class));
        verify(orderMapper, never()).updateFxRate(any(), any());
    }

    @Test
    void aDollarOrderIsRefusedWhenThereIsNoRateToConvertItWith() {
        givenAccountAndInstrument("100000.00", "USD");
        when(fxService.lockedRate("USD", OrderSide.BUY)).thenThrow(new FxRateUnavailableException("no rate"));

        FxRateUnavailableException refusal = assertThrows(FxRateUnavailableException.class,
                () -> service.placeOrder(buy(10, "100.00", "fx-norate-1"), 1L));

        org.junit.jupiter.api.Assertions.assertEquals("MKT-503", refusal.code());
        verify(orderMapper, never()).insert(any(), anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    }
}
