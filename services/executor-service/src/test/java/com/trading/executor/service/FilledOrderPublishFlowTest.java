package com.trading.executor.service;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.api.event.TradeEventPayload;
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
import com.trading.executor.event.EventEnvelope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One filled order, run through the real OrderExecutionService, OrderSettlementService and
 * KafkaOrderRepository (only the database mappers, the quote source and the Kafka template are
 * stubbed), when the trade-events publish fails and the ORDER_PLACED message is redelivered.
 *
 * What must hold: money moves once, the fill is published exactly once when the broker is back,
 * and a further duplicate delivery publishes nothing.
 */
@ExtendWith(MockitoExtension.class)
class FilledOrderPublishFlowTest {

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
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Test
	@SuppressWarnings("unchecked")
	void aFillWhoseEventTimesOutIsPublishedOnRedeliveryWithoutMovingMoneyTwice() {
		UUID orderId = UUID.randomUUID();
		OrderExecutionService executor = new OrderExecutionService(
				new OrderSettlementService(orderMapper, accountMapper, positionMapper, instrumentMapper, quoteClient, TestMarketHours.nseOpen(), org.mockito.Mockito.mock(com.trading.api.mapper.LedgerMapper.class)),
				new KafkaOrderRepository(kafkaTemplate));

		// The order is NEW on the first read; once filled, the database reports FILLED.
		when(orderMapper.findExecutionById(orderId))
				.thenReturn(order(orderId, OrderStatus.NEW), order(orderId, OrderStatus.FILLED));
		when(accountMapper.findById(1L)).thenReturn(account("1000.00"));
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true));
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);
		when(quoteClient.getQuote("INFY.NS"))
				.thenReturn(new Quote("INFY.NS", new BigDecimal("95.00"), new BigDecimal("94.995"), new BigDecimal("95.005")));
		when(orderMapper.markFilled(eq(orderId), eq(new BigDecimal("95.01")), anyLong(), any())).thenReturn(1);
		when(accountMapper.updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4)).thenReturn(1);
		when(positionMapper.insert(1L, "INFY.NS", 10, new BigDecimal("95.01"))).thenReturn(1);

		// The broker fails the publish three times (the in-place retries), then recovers.
		CompletableFuture<SendResult<String, Object>> down =
				CompletableFuture.failedFuture(new RuntimeException("broker down"));
		CompletableFuture<SendResult<String, Object>> up = CompletableFuture.completedFuture(acknowledged());
		when(kafkaTemplate.send(eq("trade-events"), eq("1"), any())).thenReturn(down, down, down, up);

		OrderPlacedEvent.Payload message = payload(orderId);

		// Delivery 1: settles and commits, but the fill cannot be published.
		assertThrows(TradeEventPublishException.class, () -> executor.execute(message));

		// Delivery 2 (the redelivery): publishes the kept fill and does not settle again.
		SettlementOutcome redelivered = executor.execute(message);
		assertTrue(redelivered.filled());

		// Delivery 3 (a further duplicate): the order is already FILLED, so nothing is published.
		SettlementOutcome duplicate = executor.execute(message);
		assertEquals(false, duplicate.settled());

		// Money moved exactly once.
		verify(orderMapper, times(1)).markFilled(eq(orderId), any(), anyLong(), any());
		verify(accountMapper, times(1)).updateCashBalanceWithVersion(1L, new BigDecimal("49.90"), 4);
		verify(positionMapper, times(1)).insert(1L, "INFY.NS", 10, new BigDecimal("95.01"));

		// Four send attempts in total (three failed, one succeeded), and nothing for the duplicate.
		ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
		verify(kafkaTemplate, times(4)).send(eq("trade-events"), eq("1"), sent.capture());
		List<Object> events = sent.getAllValues();
		EventEnvelope<TradeEventPayload> published =
				(EventEnvelope<TradeEventPayload>) events.get(3);
		assertEquals("ORDER_FILLED", published.eventType());
		assertEquals(orderId, published.payload().orderId());
		assertEquals(new BigDecimal("95.01"), published.payload().executedPrice());
		assertEquals(new BigDecimal("-950.10"), published.payload().cashDelta());
		assertEquals(10, published.payload().positionQuantityAfter());
	}

	private static SendResult<String, Object> acknowledged() {
		RecordMetadata metadata = new RecordMetadata(new TopicPartition("trade-events", 1), 0, 0, 0L, 0, 0);
		return new SendResult<>(new ProducerRecord<>("trade-events", "1", null), metadata);
	}

	private static OrderPlacedEvent.Payload payload(UUID orderId) {
		return new OrderPlacedEvent.Payload(orderId, 1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "idem-key");
	}

	private static OrderExecutionRow order(UUID id, OrderStatus status) {
		OrderExecutionRow row = new OrderExecutionRow();
		row.setId(id);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(OrderSide.BUY);
		row.setQuantity(10);
		row.setLimitPrice(new BigDecimal("100.00"));
		row.setStatus(status);
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
}
