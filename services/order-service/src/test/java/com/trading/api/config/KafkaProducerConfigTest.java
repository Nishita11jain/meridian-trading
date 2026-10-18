package com.trading.api.config;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.domain.OrderSide;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaProducerConfigTest {

	@Test
	void orderPlacedIsSerialisedAsTheContractJson() {
		ProducerFactory<String, Object> factory = new KafkaProducerConfig().producerFactory("localhost:9092");
		Serializer<Object> serializer = factory.getValueSerializerSupplier().get();

		OrderPlacedEvent event = new OrderPlacedEvent(
				UUID.randomUUID().toString(),
				"ORDER_PLACED",
				Instant.parse("2026-09-28T09:14:22Z"),
				"trade-api",
				1,
				new OrderPlacedEvent.Payload(
						UUID.randomUUID(), 1L, "AAPL", OrderSide.BUY, 100,
						new BigDecimal("233.00"), "k-1", Instant.parse("2026-09-28T09:14:22Z")));

		String json = new String(serializer.serialize("orders", event), StandardCharsets.UTF_8);

		assertTrue(json.contains("\"eventTime\":\"2026-09-28T09:14:22Z\""), json);
		assertTrue(json.contains("\"createdOn\":\"2026-09-28T09:14:22Z\""), json);
		assertTrue(json.contains("\"price\":233.00"), json);
		assertTrue(json.contains("\"source\":\"trade-api\""), json);
		assertFalse(json.contains("limitPrice"), json);
	}
}
