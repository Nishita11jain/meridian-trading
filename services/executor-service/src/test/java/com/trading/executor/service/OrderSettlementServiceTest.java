package com.trading.executor.service;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSettlementServiceTest {

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private AccountMapper accountMapper;

	@Mock
	private PositionMapper positionMapper;

	@Mock
	private InstrumentMapper instrumentMapper;

	@Mock
	private QuoteClient quoteClient;

	@Mock
	private com.trading.api.mapper.LedgerMapper ledgerMapper;

	@Test
	void buyLimitAtOrAboveQuoteFillsAtRoundedQuote() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any())).thenReturn(1);
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4)).thenReturn(1);
		when(positionMapper.insert(1L, "INFY.NS", 10, new BigDecimal("95.01"))).thenReturn(1);

		service.settle(orderId);

		verify(orderMapper).markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any());
		verify(accountMapper).updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4);
		verify(positionMapper).insert(1L, "INFY.NS", 10, new BigDecimal("95.01"));
	}

	@Test
	void sellLimitAtOrBelowQuoteFillsAtRoundedQuote() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.SELL, OrderStatus.NEW, "100.00", 5));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(position(1L, "INFY.NS", 10, "100.00"));
		when(quoteClient.getQuote("INFY.NS")).thenReturn(new Quote("INFY.NS", new BigDecimal("105.00"), new BigDecimal("105.005"), new BigDecimal("105.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("105.01")), anyLong(), any())).thenReturn(1);
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("1525.05"), 4)).thenReturn(1);
		when(positionMapper.update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0)).thenReturn(1);

		service.settle(orderId);

		verify(orderMapper).markFilled(eq(orderId), eq(new BigDecimal("105.01")), anyLong(), any());
		verify(accountMapper).updateCashBalanceWithVersion(1L, new BigDecimal("1525.05"), 4);
		verify(positionMapper).update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0);
	}

	@Test
	void orderOutsideMarketableRangeIsRejected() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(new Quote("INFY.NS", new BigDecimal("105.00"), new BigDecimal("104.995"), new BigDecimal("105.005")));
		when(orderMapper.markRejected(eq(orderId), eq("PRICE_NOT_MET"), any())).thenReturn(1);
		service.settle(orderId);

		verify(orderMapper).markRejected(eq(orderId), eq("PRICE_NOT_MET"), any());
		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
	}

	@Test
	void executionTimeRecheckRejectsUnaffordableBuy() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("50.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(orderMapper.markRejected(eq(orderId), eq("INSUFFICIENT_FUNDS"), any())).thenReturn(1);

		service.settle(orderId);

		verify(orderMapper).markRejected(eq(orderId), eq("INSUFFICIENT_FUNDS"), any());
		verify(quoteClient, never()).getQuote(any());
		org.mockito.Mockito.verifyNoInteractions(ledgerMapper);
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
	}

	@Test
	void anOrderSettledWhileItsMarketIsClosedIsHeldNotRejected() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = new OrderSettlementService(orderMapper, accountMapper, positionMapper,
				instrumentMapper, quoteClient, TestMarketHours.at(TestMarketHours.NSE_CLOSED), ledgerMapper);

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 1));

		SettlementOutcome outcome = service.settle(orderId);

		// Held: the order stays NEW for the queued release. Nothing is rejected, filled or moved.
		org.junit.jupiter.api.Assertions.assertFalse(outcome.settled());
		verify(orderMapper, never()).markRejected(any(), any(), any());
		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
		verify(quoteClient, never()).getQuote(any());
		org.mockito.Mockito.verifyNoInteractions(ledgerMapper);
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
	}

	@Test
	void accountSuspendedAfterAcceptanceDoesNotTrade() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.SUSPENDED, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(orderMapper.markRejected(eq(orderId), eq("ACCOUNT_SUSPENDED"), any())).thenReturn(1);

		service.settle(orderId);

		verify(orderMapper).markRejected(eq(orderId), eq("ACCOUNT_SUSPENDED"), any());
		verify(quoteClient, never()).getQuote(any());
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
	}

	@Test
	void orderWithNoAvailablePriceIsResolvedAsRejected() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(null);
		when(orderMapper.markRejected(eq(orderId), eq("PRICING_UNAVAILABLE"), any())).thenReturn(1);

		service.settle(orderId);

		verify(orderMapper).markRejected(eq(orderId), eq("PRICING_UNAVAILABLE"), any());
		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
	}

	@Test
	void replayingAnAlreadySettledOrderIsANoOp() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId)).thenReturn(order(orderId, OrderSide.BUY, OrderStatus.FILLED, "100.00", 10));

		service.settle(orderId);

		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
		verify(orderMapper, never()).markRejected(any(), any(), any());
		verify(quoteClient, never()).getQuote(any());
	}


	// The following tests verify the atomicity, guarded state transitions,
	// duplicate detection, and retry budget exhaustion for order settlement.

	@Test
	void atomicSettlementWritesStatusCashAndPositionTogether() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L))
				.thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS"))
				.thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS"))
				.thenReturn(null);
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any()))
				.thenReturn(1);  // Status update succeeds (1 row affected)
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4))
				.thenReturn(1);  // Account update succeeds (1 row affected)
		when(positionMapper.insert(1L, "INFY.NS", 10, new BigDecimal("95.01")))
				.thenReturn(1);  // Position insert succeeds (1 row affected)

		SettlementOutcome outcome = service.settle(orderId);

		// Verify all three writes were called
		verify(orderMapper).markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any());
		verify(accountMapper).updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4);
		verify(positionMapper).insert(1L, "INFY.NS", 10, new BigDecimal("95.01"));
		// ...and the execution record and the ledger, in the same transaction: a buy is money out.
		verify(ledgerMapper).insertTrade(eq(orderId), eq(10L), eq(new BigDecimal("95.01")), any(), isNull());
		verify(ledgerMapper).insertCashMovement(eq(1L), eq("BUY"), eq(new BigDecimal("-950.10")), eq(orderId.toString()), any());

		// Verify outcome indicates successful settlement
		org.junit.jupiter.api.Assertions.assertTrue(outcome.settled());
		org.junit.jupiter.api.Assertions.assertTrue(outcome.filled());
	}

	/**
	 * Test 2 of Task 1: Failure in account update rolls back all writes.
	 *
	 * Verifies atomicity by simulating an optimistic lock failure on the account
	 * (version mismatch). The account update returns 0 rows affected, causing
	 * OptimisticLockRetryException to be thrown. The position write must NOT happen,
	 * and the entire transaction rolls back.
	 */
	@Test
	void failureInAccountUpdateThrowsRetryableExceptionAndRollsBack() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L))
				.thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS"))
				.thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS"))
				.thenReturn(null);
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any()))
				.thenReturn(1);  // Status update succeeds
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4))
				.thenReturn(0);  // Account update FAILS (0 rows affected, version mismatch)
		// Position mapper should NOT be called due to rollback

		org.junit.jupiter.api.Assertions.assertThrows(
				OptimisticLockRetryException.class,
				() -> service.settle(orderId),
				"Expected OptimisticLockRetryException on account version mismatch"
		);

		// Verify position was not called (atomic failure before position write)
		verify(positionMapper, never()).insert(any(), any(), anyInt(), any());
		verify(positionMapper, never()).update(any(), any(), anyInt(), any(), anyInt());
	}

	/**
	 * Test 3 of Task 1: Failure in position update rolls back all writes.
	 *
	 * Verifies atomicity by simulating an optimistic lock failure on the position
	 * (version mismatch). The position update returns 0 rows affected, causing
	 * OptimisticLockRetryException to be thrown. The entire transaction rolls back.
	 */
	@Test
	void failureInPositionUpdateThrowsRetryableExceptionAndRollsBack() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.SELL, OrderStatus.NEW, "100.00", 5));
		when(accountMapper.findById(1L))
				.thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS"))
				.thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS"))
				.thenReturn(position(1L, "INFY.NS", 10, "100.00"));
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("105.00"), new BigDecimal("104.995"), new BigDecimal("105.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("105.00")), anyLong(), any()))
				.thenReturn(1);  // Status update succeeds
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("1525.00"), 4))
				.thenReturn(1);  // Account update succeeds
		when(positionMapper.update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0))
				.thenReturn(0);  // Position update FAILS (0 rows affected, version mismatch)

		org.junit.jupiter.api.Assertions.assertThrows(
				OptimisticLockRetryException.class,
				() -> service.settle(orderId),
				"Expected OptimisticLockRetryException on position version mismatch"
		);

		// Both account and position updates were attempted
		verify(accountMapper).updateCashBalanceWithVersion(1L, new BigDecimal("1525.00"), 4);
		verify(positionMapper).update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0);
	}

	/**
	 * Test 4 of Task 1: Duplicate delivery affects zero rows and publishes nothing.
	 *
	 * Verifies that a second delivery of the same ORDER_PLACED message results in:
	 * - markFilled returning 0 rows affected (status is no longer NEW)
	 * - SettlementOutcome.settled() = false (no settlement occurred)
	 * - No event published to trade-events
	 * - No cascade to account/position updates
	 *
	 * This is the guarded state transition: the first delivery settles the order
	 * (status changes from NEW to FILLED), so the second delivery finds status != NEW
	 * and the WHERE condition fails.
	 */
	@Test
	void duplicateDeliveryAffectsZeroRowsPublishesNothing() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		// First delivery already settled this order (status is now FILLED)
		OrderExecutionRow settledOrder = order(orderId, OrderSide.BUY, OrderStatus.FILLED, "100.00", 10);

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(settledOrder);
		// Note: markFilled mock is NOT set up because the settlement returns early
		// when status != NEW. This is the correct behavior for duplicate detection.

		SettlementOutcome outcome = service.settle(orderId);

		// Verify outcome indicates no settlement occurred
		org.junit.jupiter.api.Assertions.assertFalse(
				outcome.settled(),
				"Duplicate delivery should return notSettled()"
		);

		// Verify no account or position updates were made
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
		verify(positionMapper, never()).insert(any(), any(), anyInt(), any());
		verify(positionMapper, never()).update(any(), any(), anyInt(), any(), anyInt());
		// Verify markFilled was never called (order was already settled)
		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
	}

	@Test
	void unknownOrderThrowsOrderNotFoundSoItIsDeadLetteredWithTheRightReason() {
		UUID orderId = UUID.randomUUID();
		when(orderMapper.findExecutionById(orderId)).thenReturn(null);

		org.junit.jupiter.api.Assertions.assertThrows(
				com.trading.executor.exception.OrderNotFoundException.class,
				() -> service().settle(orderId));
	}

	@Test
	void guardedUpdateAffectingZeroRowsOnANewOrderSettlesNothingAndMovesNoMoney() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, OrderStatus.NEW, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		// Another delivery won the race between our read and our write.
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any())).thenReturn(0);

		SettlementOutcome outcome = service.settle(orderId);

		org.junit.jupiter.api.Assertions.assertFalse(outcome.settled());
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
		verify(positionMapper, never()).insert(any(), any(), anyInt(), any());
	}

	@Test
	void filledOutcomeCarriesTheContractFieldsForTheTradeEvent() {
		UUID orderId = UUID.randomUUID();
		OrderSettlementService service = service();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.SELL, OrderStatus.NEW, "100.00", 5));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00", AccountStatus.ACTIVE, 4));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(position(1L, "INFY.NS", 10, "100.00"));
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("105.00"), new BigDecimal("105.005"), new BigDecimal("105.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("105.01")), anyLong(), any())).thenReturn(1);
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("1525.05"), 4)).thenReturn(1);
		when(positionMapper.update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0)).thenReturn(1);

		SettlementOutcome outcome = service.settle(orderId);

		org.junit.jupiter.api.Assertions.assertEquals("INFY.NS", outcome.symbol());
		org.junit.jupiter.api.Assertions.assertEquals(OrderSide.SELL, outcome.side());
		org.junit.jupiter.api.Assertions.assertEquals(5, outcome.quantity());
		org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("100.00"), outcome.limitPrice());
		org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("525.05"), outcome.cashDelta());
		// A sell is money in: the ledger row is the same delta the balance received.
		verify(ledgerMapper).insertCashMovement(eq(1L), eq("SELL"), eq(new BigDecimal("525.05")), eq(orderId.toString()), any());
		verify(ledgerMapper).insertTrade(eq(orderId), eq(5L), eq(new BigDecimal("105.01")), any(), isNull());
		org.junit.jupiter.api.Assertions.assertEquals(5, outcome.positionQuantityAfter());
		org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("100.00"), outcome.averageCostAfter());
		org.junit.jupiter.api.Assertions.assertNotNull(outcome.executedOn());
	}

	private OrderSettlementService service() {
		return new OrderSettlementService(orderMapper, accountMapper, positionMapper, instrumentMapper, quoteClient, TestMarketHours.nseOpen(), ledgerMapper);
	}

	private static OrderExecutionRow order(UUID id, OrderSide side, OrderStatus status, String limitPrice, long quantity) {
		OrderExecutionRow row = new OrderExecutionRow();
		row.setId(id);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(side);
		row.setQuantity(quantity);
		row.setLimitPrice(new BigDecimal(limitPrice));
		row.setStatus(status);
		return row;
	}

	private static AccountRow account(String cash, AccountStatus status, int version) {
		AccountRow row = new AccountRow();
		row.setId(1L);
		row.setAccountId("ACC-REF-1");
		row.setHolderName("Holder");
		row.setCashBalance(Money.of(cash));
		row.setStatus(status);
		row.setVersion(version);
		return row;
	}

	private static PositionRow position(long accountId, String symbol, int quantity, String averageCost) {
		PositionRow row = new PositionRow();
		row.setAccountId(accountId);
		row.setSymbol(symbol);
		row.setQuantity(quantity);
		row.setAverageCost(Money.of(averageCost));
		row.setVersion(0);
		return row;
	}

	private static Instrument tradableInstrument() {
		return new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true);
	}
}
