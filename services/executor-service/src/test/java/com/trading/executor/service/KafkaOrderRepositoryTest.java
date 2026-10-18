package com.trading.executor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.config.KafkaProducerConfig;
import com.trading.domain.OrderSide;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The class that puts a settled order on trade-events. Filled orders get the same coverage
 * as their settlement: the message a consumer would read is checked field by field against
 * contracts/event-schemas/kafka-topics.md, serialised through the same serializer the service uses.
 */
@ExtendWith(MockitoExtension.class)
class KafkaOrderRepositoryTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final UUID ORDER_ID = UUID.fromString("6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e");
	private static final Instant EXECUTED_ON = Instant.parse("2026-09-28T09:14:24Z");

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Test
	void aFilledOrderIsPublishedAsOrderFilledWithEveryContractField() throws Exception {
		acknowledgeSends();

		new KafkaOrderRepository(kafkaTemplate).markFilled(filled());

		JsonNode event = publishedEvent();
		assertEquals("ORDER_FILLED", event.get("eventType").asText());
		assertEquals("trade-executor", event.get("source").asText());
		assertEquals(1, event.get("schemaVersion").asInt());
		assertTrue(event.get("eventTime").asText().endsWith("Z"), "eventTime must be RFC 3339: " + event);
		assertEquals(36, event.get("eventId").asText().length());

		JsonNode payload = event.get("payload");
		assertEquals(ORDER_ID.toString(), payload.get("orderId").asText());
		assertEquals(4, payload.get("accountId").asLong());
		assertEquals("TCS.NS", payload.get("symbol").asText());
		assertEquals("BUY", payload.get("side").asText());
		assertEquals(10, payload.get("quantity").asInt());
		assertEquals(0, new BigDecimal("5000.00").compareTo(payload.get("price").decimalValue()));
		assertEquals(0, new BigDecimal("4980.50").compareTo(payload.get("executedPrice").decimalValue()));
		assertEquals("FILLED", payload.get("status").asText());
		assertTrue(payload.get("reason").isNull());
		assertEquals(0, new BigDecimal("-49805.00").compareTo(payload.get("cashDelta").decimalValue()));
		assertEquals(10, payload.get("positionQuantityAfter").asInt());
		assertEquals(0, new BigDecimal("4980.50").compareTo(payload.get("averageCostAfter").decimalValue()));
		assertEquals("2026-09-28T09:14:24Z", payload.get("executedOn").asText());
	}

	@Test
	void aFilledOrderIsKeyedByAccountOnTradeEvents() {
		acknowledgeSends();

		new KafkaOrderRepository(kafkaTemplate).markFilled(filled());

		verify(kafkaTemplate).send(eq("trade-events"), eq("4"), any());
	}

	@Test
	void aRejectedOrderIsPublishedAsOrderRejectedWithNoMoneyMoved() throws Exception {
		acknowledgeSends();
		SettlementOutcome rejected = SettlementOutcome.rejected(
				ORDER_ID, 4L, "TCS.NS", OrderSide.BUY, 10, new BigDecimal("5000.00"),
				"PRICE_NOT_MET", 0, new BigDecimal("0.00"), EXECUTED_ON);

		new KafkaOrderRepository(kafkaTemplate).markRejected(rejected);

		JsonNode payload = publishedEvent().get("payload");
		assertEquals("ORDER_REJECTED", publishedEvent().get("eventType").asText());
		assertEquals("REJECTED", payload.get("status").asText());
		assertEquals("PRICE_NOT_MET", payload.get("reason").asText());
		assertTrue(payload.get("executedPrice").isNull());
		assertEquals(0, BigDecimal.ZERO.compareTo(payload.get("cashDelta").decimalValue()));
		assertEquals(0, payload.get("positionQuantityAfter").asInt());
	}

	@Test
	void aBrokerTimeoutIsReportedSoTheCallerCanRetry() {
		CompletableFuture<SendResult<String, Object>> neverAcknowledged = new CompletableFuture<>() {
			@Override
			public SendResult<String, Object> get(long timeout, TimeUnit unit) throws TimeoutException {
				throw new TimeoutException("no acknowledgement");
			}
		};
		when(kafkaTemplate.send(eq("trade-events"), eq("4"), any())).thenReturn(neverAcknowledged);

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> new KafkaOrderRepository(kafkaTemplate).markFilled(filled()));

		assertTrue(failure.getMessage().contains("timeout"), failure.getMessage());
		assertTrue(failure.getMessage().contains(ORDER_ID.toString()), failure.getMessage());
	}

	@Test
	void aRejectedSendIsReportedSoTheCallerCanRetry() {
		when(kafkaTemplate.send(eq("trade-events"), eq("4"), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> new KafkaOrderRepository(kafkaTemplate).markFilled(filled()));

		assertTrue(failure.getMessage().contains("Kafka publish failed"), failure.getMessage());
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static SettlementOutcome filled() {
		return SettlementOutcome.filled(
				ORDER_ID, 4L, "TCS.NS", OrderSide.BUY, 10,
				new BigDecimal("5000.00"), new BigDecimal("4980.50"),
				new BigDecimal("-49805.00"), 10, new BigDecimal("4980.50"), EXECUTED_ON);
	}

	private void acknowledgeSends() {
		RecordMetadata metadata = new RecordMetadata(new TopicPartition("trade-events", 1), 0, 0, 0L, 0, 0);
		SendResult<String, Object> result = new SendResult<>(new ProducerRecord<>("trade-events", "4", null), metadata);
		when(kafkaTemplate.send(eq("trade-events"), any(String.class), any()))
				.thenReturn(CompletableFuture.completedFuture(result));
	}

	/** Serialises the captured envelope with the service's own producer serializer. */
	private JsonNode publishedEvent() throws Exception {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(kafkaTemplate).send(eq("trade-events"), any(String.class), captor.capture());
		Serializer<Object> serializer =
				new KafkaProducerConfig().producerFactory("localhost:9092").getValueSerializerSupplier().get();
		return JSON.readTree(new String(serializer.serialize("trade-events", captor.getValue()), StandardCharsets.UTF_8));
	}
}
