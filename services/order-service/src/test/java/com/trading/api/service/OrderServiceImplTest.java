package com.trading.api.service;

import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.OrderResponse;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderHistoryRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import com.trading.api.exception.OrderNotFoundException;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

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
        service = new OrderServiceImpl(accountMapper, instrumentMapper, positionMapper, orderMapper,
                                new OrderPlacementService(key -> false), orderEventPublisher, rejectedOrderRecorder, org.mockito.Mockito.mock(TradingHours.class), org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
    }

    @Test
    void placeOrderAcceptsAsNewWithoutMovingCashOrPosition() {
        // Cash and the position move once, at settlement (Trade Executor),
        // not at acceptance -- moving them here too would double the debit
        // for every order, not just a replayed one.
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-001");
        AccountRow account = account("10000.00", AccountStatus.ACTIVE, 4);
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account);
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);

        OrderResponse response = service.placeOrder(request, 1L);

        assertTrue(response.orderId().startsWith("ORD-"));
        // Sprint 6: orders are accepted as NEW, not FILLED.
        // Trade Executor decides FILLED/REJECTED later.
        assertEquals(OrderStatus.NEW, response.status());
        assertEquals("Order accepted", response.message());
        assertEquals("INFY.NS", response.symbol());
        assertEquals(OrderSide.BUY, response.side());
        assertEquals(10, response.quantity());
        assertEquals(new BigDecimal("100.00"), response.price());
        verify(orderMapper).insert(any(UUID.class), eq(1L), eq("INFY.NS"), eq(OrderSide.BUY), eq(10L),
                eq(null), eq(new BigDecimal("100.00")), eq(OrderStatus.NEW),
                eq("idem-key-001"), any(Instant.class));
        verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(positionMapper, never()).insert(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(positionMapper, never()).update(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(orderEventPublisher).publishOrderPlaced(any());
    }

    @Test
    void placeOrderStillAnswersNewWhenTheBrokerPublishFailsAfterCommit() {
        // The row is committed at NEW; the reconciler replays it. The client must not see an error.
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-broker-down");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        org.mockito.Mockito.doThrow(new OrderPublishException("broker down", new RuntimeException()))
                .when(orderEventPublisher).publishOrderPlaced(any());

        OrderResponse response = service.placeOrder(request, 1L);

        assertEquals(OrderStatus.NEW, response.status());
        verify(orderMapper).insert(any(UUID.class), eq(1L), eq("INFY.NS"), eq(OrderSide.BUY), eq(10L),
                eq(null), eq(new BigDecimal("100.00")), eq(OrderStatus.NEW),
                eq("idem-key-broker-down"), any(Instant.class));
    }

    @Test
    void placeOrderForSomeoneElsesAccountIsRefusedWithoutLookingTheAccountUp() {
        // Ownership is checked before existence, so "not yours" and "does not exist" look the same.
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-001");

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.placeOrder(request, 2L));

        assertEquals("ACC-403", exception.code());
        verify(accountMapper, never()).findById(any());
    }

    @Test
    void placeOrderForAnAccountThatDoesNotExistAtAllLooksIdenticalToSomeoneElsesAccount() {
        PlaceOrderRequest unknownAccount = request(OrderSide.BUY, 10, "100.00", "idem-key-002");

        // Token belongs to account 2; the request names account 1 (which may or may not exist).
        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.placeOrder(unknownAccount, 2L));

        assertEquals("ACC-403", exception.code());
    }

    @Test
    void placeOrderForTheCallersOwnMissingAccountIs404() {
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-003");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(null);

        AccountNotFoundException exception = assertThrows(AccountNotFoundException.class,
                () -> service.placeOrder(request, 1L));

        assertEquals("ACC-404", exception.code());
    }

    @Test
    void placeOrderRejectionIsPersistedInItsOwnTransactionEvenThoughTheMainWriteNeverHappens() {
        // Insufficient funds -- rules 6 fails, no Account/Position write
        // should ever be attempted, but the rejection itself must still
        // reach the audit trail via the separate-transaction recorder.
        PlaceOrderRequest request = request(OrderSide.BUY, 999999, "999999.00", "idem-key-reject-001");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("100.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);

        assertThrows(InsufficientFundsException.class, () -> service.placeOrder(request, 1L));

        verify(rejectedOrderRecorder).recordRejection(request, RejectedOrderRecorder.INSUFFICIENT_FUNDS);
        verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(orderMapper, never()).insert(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void retryingAnUnaffordableOrderWithTheSameKeyStillAnswersOrd400NotAServerError() {
        // The first attempt already recorded a REJECTED row under this key, so recording it
        // again hits the unique constraint. That must not turn the 400 into a 500.
        PlaceOrderRequest request = request(OrderSide.BUY, 999999, "999999.00", "idem-key-retry-001");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("100.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uq_orders_idempotency_key"))
                .when(rejectedOrderRecorder).recordRejection(request, RejectedOrderRecorder.INSUFFICIENT_FUNDS);

        InsufficientFundsException exception = assertThrows(InsufficientFundsException.class,
                () -> service.placeOrder(request, 1L));

        assertEquals("ORD-400", exception.code());
    }

    @Test
    void anUnaffordableOrderIsAnnouncedAsOrderRejectedWithItsReason() {
        PlaceOrderRequest request = request(OrderSide.BUY, 999999, "999999.00", "idem-key-announce-001");
        java.util.UUID rejectedId = java.util.UUID.randomUUID();
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("100.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        when(rejectedOrderRecorder.recordRejection(request, RejectedOrderRecorder.INSUFFICIENT_FUNDS)).thenReturn(rejectedId);

        assertThrows(InsufficientFundsException.class, () -> service.placeOrder(request, 1L));

        verify(orderEventPublisher).publishOrderRejected(rejectedId, request, RejectedOrderRecorder.INSUFFICIENT_FUNDS, null);
    }

    @Test
    void aBrokerFailureWhileAnnouncingARejectionStillAnswersTheOriginal400() {
        PlaceOrderRequest request = request(OrderSide.BUY, 999999, "999999.00", "idem-key-announce-002");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("100.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        when(rejectedOrderRecorder.recordRejection(any(), any())).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.doThrow(new OrderPublishException("broker down", null))
                .when(orderEventPublisher).publishOrderRejected(any(), any(), any(), any());

        InsufficientFundsException exception = assertThrows(InsufficientFundsException.class,
                () -> service.placeOrder(request, 1L));

        assertEquals("ORD-400", exception.code());
    }

    @Test
    void aRetriedRejectionThatIsAlreadyRecordedIsNotAnnouncedTwice() {
        PlaceOrderRequest request = request(OrderSide.BUY, 999999, "999999.00", "idem-key-announce-003");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("100.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uq_orders_idempotency_key"))
                .when(rejectedOrderRecorder).recordRejection(any(), any());

        assertThrows(InsufficientFundsException.class, () -> service.placeOrder(request, 1L));

        verify(orderEventPublisher, never()).publishOrderRejected(any(), any(), any(), any());
    }

    @Test
    void placeOrderTranslatesARealUniqueConstraintViolationIntoOrd409() {
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-race-001");
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
        when(orderMapper.insert(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uq_orders_idempotency_key"));

        DuplicateOrderException exception = assertThrows(DuplicateOrderException.class,
                () -> service.placeOrder(request, 1L));

        assertEquals("ORD-409", exception.code());
    }

    @Test
    void cancelOrderRejectsAnAlreadyFilledOrder() {
        UUID id = UUID.randomUUID();
        OrderHistoryRow filled = orderRow("ORD-" + id, 1L, "TCS.NS", OrderSide.BUY, 10,
                new BigDecimal("100.00"), new BigDecimal("99.50"), OrderStatus.FILLED, "idem-key-00000001",
                Instant.now());
        when(orderMapper.findById(id)).thenReturn(filled);

        assertThatThrownBy(() -> service.cancelOrder(id, 1L))
                .isInstanceOf(DuplicateOrderException.class)
                .satisfies(ex -> assertThat(((DuplicateOrderException) ex).code()).isEqualTo("ORD-409"))
                .hasMessageContaining("not cancellable");

        verify(orderMapper, never()).cancelIfNew(any());
    }

    @Test
    void cancelOrderRejectsFilledOrder() {
        UUID orderId = UUID.randomUUID();
        OrderHistoryRow existing = orderRow("ORD-124", 1L, "INFY.NS", OrderSide.SELL, 5,
                new BigDecimal("150.00"), null, OrderStatus.FILLED, "idem-key-003", Instant.now());
        when(orderMapper.findById(orderId)).thenReturn(existing);

        DuplicateOrderException exception = assertThrows(DuplicateOrderException.class,
                () -> service.cancelOrder(orderId, 1L));

        assertEquals("ORD-409", exception.code());
        assertEquals("Order is not cancellable", exception.getMessage());
        verify(orderMapper, never()).cancelIfNew(orderId);
    }

    @Test
    void cancelOrderRejectsATokenForADifferentAccount() {
        UUID id = UUID.randomUUID();
        OrderHistoryRow belongsToAccountOne = orderRow("ORD-" + id, 1L, "TCS.NS", OrderSide.BUY, 10,
                new BigDecimal("100.00"), null, OrderStatus.NEW, "idem-key-00000001", Instant.now());
        when(orderMapper.findById(id)).thenReturn(belongsToAccountOne);

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.cancelOrder(id, 2L));

        assertEquals("ACC-403", exception.code());
        verify(orderMapper, never()).cancelIfNew(any());
    }

    @Test
    void cancelOrderReturnsCancelledResponseForNewOrder() {
        UUID orderId = UUID.randomUUID();
        OrderHistoryRow existing = orderRow("ORD-123", 1L, "INFY.NS", OrderSide.SELL, 5,
                new BigDecimal("150.00"), null, OrderStatus.NEW, "idem-key-002", Instant.now());
        when(orderMapper.findById(orderId)).thenReturn(existing);
        when(orderMapper.cancelIfNew(orderId)).thenReturn(1);

        OrderResponse response = service.cancelOrder(orderId, 1L);

        assertEquals("ORD-123", response.orderId());
        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals("Order cancelled", response.message());
        assertEquals(OrderSide.SELL, response.side());
        verify(orderEventPublisher).publishOrderCancelled(any(), any());
    }

    @Test
    void cancelOrderRejectsMissingOrder() {
        UUID orderId = UUID.randomUUID();
        when(orderMapper.findById(orderId)).thenReturn(null);

        OrderNotFoundException exception = assertThrows(OrderNotFoundException.class,
                () -> service.cancelOrder(orderId, 1L));

        assertEquals("ORD-409", OrderNotFoundException.CODE);
        assertEquals("Order not found", exception.getMessage());
        verify(orderEventPublisher, never()).publishOrderCancelled(any(), any());
    }

    @Test
    void getOrderHistoryRejectsUnknownAccount() {
        when(accountMapper.findById(1L)).thenReturn(null);

        AccountNotFoundException exception = assertThrows(AccountNotFoundException.class,
                () -> service.getOrderHistory(1L, 1L, OrderStatus.NEW, Instant.now(), Instant.now()));

        assertEquals("ACC-404", exception.code());
    }

    @Test
    void getOrderHistoryForAnAccountThatIsNotTheCallersOwnIsRefusedWith403() {
        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getOrderHistory(999L, 1L, OrderStatus.NEW, Instant.now(), Instant.now()));

        assertEquals("ACC-403", exception.code());
        verify(accountMapper, never()).findById(any());
    }

    @Test
    void getOrderHistoryForTheCallersOwnMissingAccountIs404() {
        when(accountMapper.findById(999L)).thenReturn(null);

        AccountNotFoundException exception = assertThrows(AccountNotFoundException.class,
                () -> service.getOrderHistory(999L, 999L, OrderStatus.NEW, Instant.now(), Instant.now()));

        assertEquals("ACC-404", exception.code());
    }

    @Test
    void getOrderHistoryRejectsATokenForADifferentAccount() {
        org.mockito.Mockito.lenient().when(accountMapper.findById(1L))
                .thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));

        AccountNotActiveException exception = assertThrows(AccountNotActiveException.class,
                () -> service.getOrderHistory(1L, 2L, OrderStatus.NEW, Instant.now(), Instant.now()));

        assertEquals("ACC-403", exception.code());
    }

    @Test
    void getOrderHistoryPassesStatusNameToMapper() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-08T00:00:00Z");
        Instant createdOn = Instant.now();
        List<OrderHistoryRow> expectedRows = List.of(orderRow("ORD-123", 1L, "INFY.NS", OrderSide.BUY,
                10, new BigDecimal("100.00"), null, OrderStatus.NEW, "idem-key-001", createdOn));
        List<OrderHistoryEntry> expected = List.of(new OrderHistoryEntry("ORD-123", 1L, "INFY.NS", OrderSide.BUY,
                10, new BigDecimal("100.00"), null, OrderStatus.NEW, "idem-key-001", createdOn, null));
        when(accountMapper.findById(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(orderMapper.findHistoryByAccount(1L, "NEW", from, to)).thenReturn(expectedRows);

        List<OrderHistoryEntry> response = service.getOrderHistory(1L, 1L, OrderStatus.NEW, from, to);

        assertEquals(expected, response);
        verify(orderMapper).findHistoryByAccount(1L, "NEW", from, to);
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

    private static Instrument tradableInstrument() {
        return new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true);
    }

    private static PlaceOrderRequest request(OrderSide side, int quantity, String price, String idempotencyKey) {
        return new PlaceOrderRequest(1L, "INFY.NS", side, quantity, new BigDecimal(price), idempotencyKey);
    }

    private static OrderHistoryRow orderRow(String orderId, Long accountId, String symbol,
            OrderSide side, int quantity, BigDecimal price, BigDecimal executedPrice,
            OrderStatus status, String idempotencyKey, Instant createdOn) {
        OrderHistoryRow row = new OrderHistoryRow();
        row.setOrderId(orderId);
        row.setAccountId(accountId);
        row.setSymbol(symbol);
        row.setSide(side);
        row.setQuantity(quantity);
        row.setPrice(price);
        row.setExecutedPrice(executedPrice);
        row.setStatus(status);
        row.setIdempotencyKey(idempotencyKey);
        row.setCreatedOn(createdOn);
        return row;
    }

    @Test
    void findOwnOrderReturnsTheOrderOnlyToItsAccount() {
        UUID orderId = UUID.randomUUID();
        when(orderMapper.findById(orderId)).thenReturn(orderRow("ORD-" + orderId, 1L, "INFY.NS", OrderSide.BUY, 5,
                new BigDecimal("100.00"), null, OrderStatus.NEW, "k", Instant.parse("2026-10-01T09:00:00Z")));

        assertEquals("ORD-" + orderId, service.findOwnOrder(orderId, 1L).orElseThrow().orderId());
        assertTrue(service.findOwnOrder(orderId, 2L).isEmpty(), "another account's order is not disclosed");
    }

    @Test
    void findOwnOrderOfAMissingOrderIsEmpty() {
        UUID orderId = UUID.randomUUID();
        when(orderMapper.findById(orderId)).thenReturn(null);

        assertTrue(service.findOwnOrder(orderId, 1L).isEmpty());
    }

    @Test
    void anOrderForAClosedMarketIsAcceptedAndQueuedNotRefused() {
        TradingHours closed = org.mockito.Mockito.mock(TradingHours.class);
        OrderServiceImpl queuing = new OrderServiceImpl(accountMapper, instrumentMapper, positionMapper, orderMapper,
                new OrderPlacementService(key -> false), orderEventPublisher, rejectedOrderRecorder, closed, org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());

        var response = queuing.placeOrder(request(OrderSide.BUY, 1, "100.00", "idem-queued"), 1L);

        assertEquals("NEW", response.status().name());
        verify(orderMapper).insert(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
        verify(orderEventPublisher).publishOrderPlaced(any());
        verify(closed, never()).requireOpen(any());
    }

    @Test
    void aBuyIsCheckedAgainstTheCashNotYetCommittedToQueuedBuys() {
        // 10,000 cash, of which 9,950 is already reserved by queued buys: a 100 buy is refused now.
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
        when(orderMapper.sumReservedCash(1L)).thenReturn(new BigDecimal("9950.00"));

        assertThrows(com.trading.domain.exception.InsufficientFundsException.class,
                () -> service.placeOrder(request(OrderSide.BUY, 1, "100.00", "idem-reserved"), 1L));
        verify(orderMapper, never()).insert(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownInstrumentIsStillIns404NotAMarketQuestion() {
        TradingHours hours = org.mockito.Mockito.mock(TradingHours.class);
        OrderServiceImpl s = new OrderServiceImpl(accountMapper, instrumentMapper, positionMapper, orderMapper,
                new OrderPlacementService(key -> false), orderEventPublisher, rejectedOrderRecorder, hours, org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
        when(accountMapper.findByIdForUpdate(1L)).thenReturn(account("10000.00", AccountStatus.ACTIVE, 4));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(null);

        assertThrows(RuntimeException.class, () -> s.placeOrder(request(OrderSide.BUY, 1, "100.00", "idem-unknown"), 1L));
        verify(hours, never()).requireOpen(any());
    }
}
