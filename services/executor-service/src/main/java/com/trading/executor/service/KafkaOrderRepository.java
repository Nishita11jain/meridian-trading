package com.trading.executor.service;

import com.trading.api.event.TradeEventPayload;
import com.trading.executor.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class KafkaOrderRepository implements OrderRepository {

	private static final String TOPIC = "trade-events";
	private static final long PUBLISH_TIMEOUT_SECONDS = 5;

	private static final Logger log = LoggerFactory.getLogger(KafkaOrderRepository.class);

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public KafkaOrderRepository(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@Override
	public void markFilled(SettlementOutcome outcome) {

		publishEvent(
				new EventEnvelope<>(
						UUID.randomUUID().toString(),
						"ORDER_FILLED",
						Instant.now(),
						"trade-executor",
						1,
						toPayload(outcome, "FILLED")
				),
				String.valueOf(outcome.accountId()),
				"ORDER_FILLED",
				outcome.orderId()
		);
	}

	@Override
	public void markRejected(SettlementOutcome outcome) {

		publishEvent(
				new EventEnvelope<>(
						UUID.randomUUID().toString(),
						"ORDER_REJECTED",
						Instant.now(),
						"trade-executor",
						1,
						toPayload(outcome, "REJECTED")
				),
				String.valueOf(outcome.accountId()),
				"ORDER_REJECTED",
				outcome.orderId()
		);
	}

	private static TradeEventPayload toPayload(SettlementOutcome outcome, String status) {
		return new TradeEventPayload(
				outcome.orderId(),
				outcome.accountId(),
				outcome.symbol(),
				outcome.side(),
				outcome.quantity(),
				outcome.limitPrice(),
				outcome.executedPrice(),
				status,
				outcome.reason(),
				outcome.cashDelta(),
				outcome.positionQuantityAfter(),
				outcome.averageCostAfter(),
				outcome.executedOn()
		);
	}

	/**
	 * Synchronously publish an event to Kafka.
	 *
	 * This is a CRITICAL call: the event is published AFTER the database
	 * transaction has committed. We wait for the Kafka send to complete
	 * before returning, so that OrderExecutionService can acknowledge the
	 * original ORDER_PLACED message only after the event has been sent.
	 *
	 * Timeout: 5 seconds. If Kafka is unreachable for longer, the exception
	 * will propagate and OrderExecutionService will retry the entire settlement.
	 *
	 * @param event the envelope to publish
	 * @param key the partition key (accountId as string)
	 * @param eventType for logging
	 * @param orderId for logging
	 * @throws RuntimeException if send fails or times out (causes retry by OrderExecutionService)
	 */
	private void publishEvent(
			EventEnvelope<?> event,
			String key,
			String eventType,
			UUID orderId) {

		try {
			SendResult<String, Object> result =
					kafkaTemplate.send(TOPIC, key, event)
							.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			log.info(
					"Event published: {} for order {} to partition {}",
					eventType, orderId, result.getRecordMetadata().partition()
			);

		} catch (TimeoutException exception) {
			throw new RuntimeException(
					"Kafka publish timeout for " + eventType + " on order " + orderId,
					exception
			);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(
					"Kafka publish interrupted for " + eventType + " on order " + orderId,
					exception
			);
		} catch (ExecutionException exception) {
			throw new RuntimeException(
					"Kafka publish failed for " + eventType + " on order " + orderId,
					exception.getCause()
			);
		}
	}
}

