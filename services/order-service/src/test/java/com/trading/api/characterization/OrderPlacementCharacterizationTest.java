package com.trading.api.characterization;

import com.trading.api.service.TradingHours;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.service.OrderEventPublisher;
import com.trading.api.service.RejectedOrderRecorder;
import com.trading.api.service.OrderServiceImpl;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlacementCharacterizationTest {

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

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(
                accountMapper,
                instrumentMapper,
                positionMapper,
                orderMapper,
                new OrderPlacementService(key -> false),
                orderEventPublisher,
                rejectedOrderRecorder,
                org.mockito.Mockito.mock(TradingHours.class), org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
    }

    @Test
    void affordableBuyReturnsNewAndPersistsOrderWithoutMovingCashOrPosition() {

        PlaceOrderRequest request =
                request(
                        OrderSide.BUY,
                        10,
                        "100.00",
                        "idem-key-001"
                );

        when(accountMapper.findByIdForUpdate(1L))
                .thenReturn(
                        account(
                                "10000.00",
                                AccountStatus.ACTIVE,
                                4
                        )
                );

        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(tradableInstrument());

        when(positionMapper.findByAccountAndSymbol(
                1L,
                "INFY.NS"
        )).thenReturn(null);

        var response =
                service.placeOrder(
                        request,
                        1L
                );

        // API accepts the order as NEW.
        assertEquals(
                OrderStatus.NEW,
                response.status()
        );

        assertThat(response.orderId())
                .startsWith("ORD-");

        assertEquals(
                "Order accepted",
                response.message()
        );

        assertEquals(
                "INFY.NS",
                response.symbol()
        );

        assertEquals(
                OrderSide.BUY,
                response.side()
        );

        assertEquals(
                10,
                response.quantity()
        );

        assertEquals(
                new BigDecimal("100.00"),
                response.price()
        );

        // Order is persisted as NEW.
        verify(orderMapper).insert(
                any(UUID.class),
                eq(1L),
                eq("INFY.NS"),
                eq(OrderSide.BUY),
                eq(10L),
                eq(null),
                eq(new BigDecimal("100.00")),
                eq(OrderStatus.NEW),
                eq("idem-key-001"),
                any()
        );

        // Fix: cash and the position move once, at settlement (Trade
        // Executor), not at acceptance. Moving them here too debited and
        // repositioned every order twice -- not just a replayed one -- since
        // OrderSettlementService performs the same movement again on FILLED.
        verify(accountMapper, never())
                .updateCashBalanceWithVersion(
                        any(),
                        any(),
                        anyInt()
                );

        verify(positionMapper, never())
                .insert(
                        any(),
                        any(),
                        anyInt(),
                        any()
                );

        // Sprint 7: accepted order publishes ORDER_PLACED.
        verify(orderEventPublisher)
                .publishOrderPlaced(any());
    }

    @Test
    void reusedIdempotencyKeyIsRejectedWithOrd409() {

        PlaceOrderRequest request =
                request(
                        OrderSide.BUY,
                        10,
                        "100.00",
                        "idem-key-duplicate"
                );

        when(accountMapper.findByIdForUpdate(1L))
                .thenReturn(
                        account(
                                "10000.00",
                                AccountStatus.ACTIVE,
                                4
                        )
                );

        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(tradableInstrument());

        when(positionMapper.findByAccountAndSymbol(
                1L,
                "INFY.NS"
        )).thenReturn(null);

        OrderServiceImpl duplicateAwareService =
                new OrderServiceImpl(
                        accountMapper,
                        instrumentMapper,
                        positionMapper,
                        orderMapper,
                        new OrderPlacementService(
                                key ->
                                        "idem-key-duplicate"
                                                .equals(key)
                        ),
                        orderEventPublisher,
                        rejectedOrderRecorder,
                        org.mockito.Mockito.mock(TradingHours.class)
                , org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));

        DuplicateOrderException exception =
                assertThrows(
                        DuplicateOrderException.class,
                        () ->
                                duplicateAwareService.placeOrder(
                                        request,
                                        1L
                                )
                );

        assertEquals(
                "ORD-409",
                exception.code()
        );

        verify(orderMapper, never())
                .insert(
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        // Rejected order must not publish ORDER_PLACED.
        verify(orderEventPublisher, never())
                .publishOrderPlaced(any());
    }

    @Test
    void unaffordableBuyIsRejectedWithOrd400() {

        PlaceOrderRequest request =
                request(
                        OrderSide.BUY,
                        10,
                        "100.00",
                        "idem-key-insufficient"
                );

        when(accountMapper.findByIdForUpdate(1L))
                .thenReturn(
                        account(
                                "50.00",
                                AccountStatus.ACTIVE,
                                4
                        )
                );

        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(tradableInstrument());

        when(positionMapper.findByAccountAndSymbol(
                1L,
                "INFY.NS"
        )).thenReturn(null);

        InsufficientFundsException exception =
                assertThrows(
                        InsufficientFundsException.class,
                        () ->
                                service.placeOrder(
                                        request,
                                        1L
                                )
                );

        assertEquals(
                "ORD-400",
                exception.code()
        );

        verify(orderMapper, never())
                .insert(
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        // Business rejection must not publish ORDER_PLACED.
        verify(orderEventPublisher, never())
                .publishOrderPlaced(any());
    }

    @Test
    void unknownSymbolIsRejectedWithIns404() {

        PlaceOrderRequest request =
                request(
                        OrderSide.BUY,
                        10,
                        "100.00",
                        "idem-key-symbol"
                );

        when(accountMapper.findByIdForUpdate(1L))
                .thenReturn(
                        account(
                                "10000.00",
                                AccountStatus.ACTIVE,
                                4
                        )
                );

        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(null);

        InstrumentNotFoundException exception =
                assertThrows(
                        InstrumentNotFoundException.class,
                        () ->
                                service.placeOrder(
                                        request,
                                        1L
                                )
                );

        assertEquals(
                "INS-404",
                exception.code()
        );

        verify(orderMapper, never())
                .insert(
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        // Failed order must not publish ORDER_PLACED.
        verify(orderEventPublisher, never())
                .publishOrderPlaced(any());
    }

    @Test
    void inactiveAccountIsRejectedWithAcc403() {

        PlaceOrderRequest request =
                request(
                        OrderSide.BUY,
                        10,
                        "100.00",
                        "idem-key-inactive"
                );

        when(accountMapper.findByIdForUpdate(1L))
                .thenReturn(
                        account(
                                "10000.00",
                                AccountStatus.SUSPENDED,
                                4
                        )
                );

        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(tradableInstrument());

        AccountNotActiveException exception =
                assertThrows(
                        AccountNotActiveException.class,
                        () ->
                                service.placeOrder(
                                        request,
                                        1L
                                )
                );

        assertEquals(
                "ACC-403",
                exception.code()
        );

        verify(orderMapper, never())
                .insert(
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        // Failed order must not publish ORDER_PLACED.
        verify(orderEventPublisher, never())
                .publishOrderPlaced(any());
    }

    private static AccountRow account(
            String cash,
            AccountStatus status,
            int version) {

        AccountRow account =
                new AccountRow();

        account.setId(1L);
        account.setAccountId("ACC-REF-1");
        account.setHolderName("Holder");
        account.setCashBalance(
                Money.of(cash)
        );
        account.setStatus(status);
        account.setVersion(version);

        return account;
    }

    private static Instrument tradableInstrument() {

        return new Instrument(
                "INFY.NS",
                "Infosys",
                "EQUITY",
                "INR",
                true
        );
    }

    private static PlaceOrderRequest request(
            OrderSide side,
            int quantity,
            String price,
            String idempotencyKey) {

        return new PlaceOrderRequest(
                1L,
                "INFY.NS",
                side,
                quantity,
                new BigDecimal(price),
                idempotencyKey
        );
    }
}