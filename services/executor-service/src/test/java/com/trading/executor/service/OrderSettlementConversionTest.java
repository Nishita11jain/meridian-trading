package com.trading.executor.service;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.LedgerMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A dollar order carries the rate locked when it was placed, and settlement moves cash in rupees at that rate while
 * the trade keeps the dollar price. The instrument here is rupee-listed for the market-hours check only; what matters
 * is the rate on the order.
 */
@ExtendWith(MockitoExtension.class)
class OrderSettlementConversionTest {

	private static final BigDecimal RATE = new BigDecimal("83.500000");

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
	private LedgerMapper ledgerMapper;

	private OrderSettlementService service() {
		return new OrderSettlementService(orderMapper, accountMapper, positionMapper, instrumentMapper, quoteClient,
				TestMarketHours.nseOpen(), ledgerMapper);
	}

	private static OrderExecutionRow dollarOrder(UUID id, OrderSide side, String limit, long quantity) {
		OrderExecutionRow row = new OrderExecutionRow();
		row.setId(id);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(side);
		row.setQuantity(quantity);
		row.setLimitPrice(new BigDecimal(limit));
		row.setStatus(OrderStatus.NEW);
		row.setFxRate(RATE);
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

	private static PositionRow position(int quantity, String averageCost) {
		PositionRow row = new PositionRow();
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setQuantity(quantity);
		row.setAverageCost(Money.of(averageCost));
		row.setVersion(0);
		return row;
	}

	private void marketAnd(Instrument instrument, String price, String bid, String ask) {
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(instrument);
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal(price), new BigDecimal(bid), new BigDecimal(ask)));
	}

	private static Instrument instrument() {
		return new Instrument("INFY.NS", "Infosys", "EQUITY", "USD", true);
	}

	@Test
	void aDollarBuyDebitsCashInRupeesAtTheLockedRateAndStoresTheRateOnTheTrade() {
		UUID orderId = UUID.randomUUID();
		when(orderMapper.findExecutionById(orderId)).thenReturn(dollarOrder(orderId, OrderSide.BUY, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("100000.00"));
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		marketAnd(instrument(), "95.00", "94.995", "95.005");
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any())).thenReturn(1);
		// 10 x 95.01 dollars = 950.10 dollars; at 83.5 that is 79,333.35 rupees out of 100,000.00.
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("20666.65"), 4)).thenReturn(1);
		when(positionMapper.insert(1L, "INFY.NS", 10, new BigDecimal("95.01"))).thenReturn(1);

		SettlementOutcome outcome = service().settle(orderId);

		assertTrue(outcome.filled());
		assertEquals(new BigDecimal("-79333.35"), outcome.cashDelta());
		verify(ledgerMapper).insertCashMovement(eq(1L), eq("BUY"), eq(new BigDecimal("-79333.35")), eq(orderId.toString()), any());
		// The trade keeps the dollar price and the rate, so its rupee value can always be recomputed.
		verify(ledgerMapper).insertTrade(eq(orderId), eq(10L), eq(new BigDecimal("95.01")), any(), eq(RATE));
		// The position stays in the instrument's own currency.
		assertEquals(new BigDecimal("95.01"), outcome.averageCostAfter());
	}

	@Test
	void aDollarBuyThatIsAffordableInDollarsButNotInRupeesIsRejected() {
		UUID orderId = UUID.randomUUID();
		// 10 x 100 = 1,000 dollars would fit in 50,000, but 83,500 rupees would not.
		when(orderMapper.findExecutionById(orderId)).thenReturn(dollarOrder(orderId, OrderSide.BUY, "100.00", 10));
		when(accountMapper.findById(1L)).thenReturn(account("50000.00"));
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(instrument());
		when(orderMapper.markRejected(eq(orderId), eq("INSUFFICIENT_FUNDS"), any())).thenReturn(1);

		SettlementOutcome outcome = service().settle(orderId);

		assertTrue(outcome.settled());
		assertEquals(false, outcome.filled());
		verify(orderMapper, never()).markFilled(any(), any(), anyLong(), any());
		verify(accountMapper, never()).updateCashBalanceWithVersion(any(), any(), anyInt());
	}

	@Test
	void aDollarSellCreditsCashInRupeesAtTheLockedRate() {
		UUID orderId = UUID.randomUUID();
		when(orderMapper.findExecutionById(orderId)).thenReturn(dollarOrder(orderId, OrderSide.SELL, "100.00", 5));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00"));
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(position(10, "100.00"));
		marketAnd(instrument(), "105.00", "105.005", "105.005");
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("105.01")), anyLong(), any())).thenReturn(1);
		// 5 x 105.01 = 525.05 dollars; at 83.5 that is 43,841.68 rupees (43,841.675 rounds half up).
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("44841.68"), 4)).thenReturn(1);
		when(positionMapper.update(1L, "INFY.NS", 5, new BigDecimal("100.00"), 0)).thenReturn(1);

		SettlementOutcome outcome = service().settle(orderId);

		assertEquals(new BigDecimal("43841.68"), outcome.cashDelta());
		verify(ledgerMapper).insertTrade(eq(orderId), eq(5L), eq(new BigDecimal("105.01")), any(), eq(RATE));
	}

	@Test
	void conversionRoundsOnceToTwoDecimalsAndLeavesARupeeAmountAlone() {
		assertEquals(new BigDecimal("79333.35"), OrderSettlementService.toRupees(new BigDecimal("950.10"), RATE));
		assertEquals(new BigDecimal("950.10"), OrderSettlementService.toRupees(new BigDecimal("950.10"), null));
	}
}
