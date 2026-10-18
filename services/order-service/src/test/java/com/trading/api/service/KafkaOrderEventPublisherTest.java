package com.trading.api.service;

import com.trading.domain.Money;
import com.trading.domain.Order;
import com.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOrderEventPublisherTest {

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Test
	void publishOrderPlacedWaitsForTheBrokerAndKeysByAccount() {
		when(kafkaTemplate.send(eq("orders"), eq("1"), any())).thenReturn(CompletableFuture.completedFuture(null));

		assertDoesNotThrow(() -> new KafkaOrderEventPublisher(kafkaTemplate).publishOrderPlaced(order()));

		verify(kafkaTemplate).send(eq("orders"), eq("1"), any());
	}

	@Test
	void failedPublishIsReportedSoTheOrderCanBeReplayed() {
		when(kafkaTemplate.send(eq("orders"), eq("1"), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

		assertThrows(OrderPublishException.class,
				() -> new KafkaOrderEventPublisher(kafkaTemplate).publishOrderPlaced(order()));
	}

	@Test
	void publishOrderCancelledWaitsForTheBrokerAndReportsAFailure() {
		when(kafkaTemplate.send(eq("trade-events"), eq("1"), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

		assertThrows(OrderPublishException.class,
				() -> new KafkaOrderEventPublisher(kafkaTemplate).publishOrderCancelled(order(), null));
	}

	@Test
	void publishOrderCancelledSucceedsWhenTheBrokerAcknowledges() {
		when(kafkaTemplate.send(eq("trade-events"), eq("1"), any())).thenReturn(CompletableFuture.completedFuture(null));

		assertDoesNotThrow(() -> new KafkaOrderEventPublisher(kafkaTemplate).publishOrderCancelled(order(), null));
	}

	private static Order order() {
		return new Order(UUID.randomUUID(), 1L, "INFY.NS", OrderSide.BUY, 10, Money.of("100.00"), "idem-1", Instant.now());
	}
}
