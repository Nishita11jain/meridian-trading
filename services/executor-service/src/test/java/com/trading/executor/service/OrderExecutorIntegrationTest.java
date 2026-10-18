package com.trading.executor.service;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderExecutionRow;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real OrderExecutionService/OrderSettlementService pair -- only
 * QuoteClient and the mappers are stubbed -- against a fill, a reject, and a
 * pricing-unavailable response, exactly as the Trade Executor story's
 * "Integration Test" asks. This is what actually catches a broken wiring
 * between settlement and the trade-events publish that a mocked
 * OrderSettlementService (as in OrderExecutionServiceTest) cannot.
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutorIntegrationTest {

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
	private OrderRepository orderRepository;

	@Test
	void fillPublishesOrderFilledToTradeEvents() {
		UUID orderId = UUID.randomUUID();
		OrderExecutionService executor = executor();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00"));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		when(orderMapper.markFilled(any(), any(), anyLong(), any())).thenReturn(1);
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4)).thenReturn(1);
		when(positionMapper.insert(1L, "INFY.NS", 10, new BigDecimal("95.01"))).thenReturn(1);

		executor.execute(payload(orderId, OrderSide.BUY, "100.00"));

		verify(orderRepository).markFilled(argThat(outcome ->
				orderId.equals(outcome.orderId())
						&& outcome.accountId() == 1L
						&& new BigDecimal("95.01").equals(outcome.executedPrice())
						&& "INFY.NS".equals(outcome.symbol())
						&& outcome.side() == OrderSide.BUY
						&& outcome.quantity() == 10
						&& new BigDecimal("100.00").equals(outcome.limitPrice())
						&& new BigDecimal("-950.10").equals(outcome.cashDelta())
						&& outcome.positionQuantityAfter() == 10
						&& new BigDecimal("95.01").equals(outcome.averageCostAfter())
						&& outcome.executedOn() != null));
		verify(orderRepository, never()).markRejected(any());
	}

	@Test
	void outOfRangeOrderPublishesOrderRejectedToTradeEvents() {
		UUID orderId = UUID.randomUUID();
		OrderExecutionService executor = executor();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00"));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(new Quote("INFY.NS", new BigDecimal("105.00"), new BigDecimal("104.995"), new BigDecimal("105.005")));
		when(orderMapper.markRejected(eq(orderId), eq("PRICE_NOT_MET"), any())).thenReturn(1);

		executor.execute(payload(orderId, OrderSide.BUY, "100.00"));

		verify(orderRepository).markRejected(argThat(outcome ->
				orderId.equals(outcome.orderId())
						&& "PRICE_NOT_MET".equals(outcome.reason())
						&& outcome.cashDelta().signum() == 0
						&& outcome.positionQuantityAfter() == 0));
		verify(orderRepository, never()).markFilled(any());
	}

	@Test
	void pricingUnavailablePublishesOrderRejectedRatherThanLeavingOrderAtNew() {
		UUID orderId = UUID.randomUUID();
		OrderExecutionService executor = executor();

		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderSide.BUY, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00"));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS")).thenReturn(null);
		when(orderMapper.markRejected(eq(orderId), eq("PRICING_UNAVAILABLE"), any())).thenReturn(1);

		executor.execute(payload(orderId, OrderSide.BUY, "100.00"));

		verify(orderRepository).markRejected(argThat(outcome ->
				orderId.equals(outcome.orderId())
						&& "PRICING_UNAVAILABLE".equals(outcome.reason())));
		verify(orderRepository, never()).markFilled(any());
	}

	private OrderExecutionService executor() {
		OrderSettlementService settlementService = new OrderSettlementService(
				orderMapper, accountMapper, positionMapper, instrumentMapper, quoteClient, TestMarketHours.nseOpen(), org.mockito.Mockito.mock(com.trading.api.mapper.LedgerMapper.class));
		return new OrderExecutionService(settlementService, orderRepository);
	}

	private static OrderPlacedEvent.Payload payload(UUID orderId, OrderSide side, String limitPrice) {
		return new OrderPlacedEvent.Payload(
				orderId, 1L, "INFY.NS", side, 10, new BigDecimal(limitPrice), "idem-key");
	}

	private static OrderExecutionRow order(UUID id, OrderSide side, String limitPrice, long quantity) {
		OrderExecutionRow row = new OrderExecutionRow();
		row.setId(id);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(side);
		row.setQuantity(quantity);
		row.setLimitPrice(new BigDecimal(limitPrice));
		row.setStatus(OrderStatus.NEW);
		return row;
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

	private static Instrument tradableInstrument() {
		return new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true);
	}
}
