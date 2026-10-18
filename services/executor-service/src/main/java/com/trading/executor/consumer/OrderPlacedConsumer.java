package com.trading.executor.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.event.OrderPlacedEvent;
import com.trading.executor.exception.MalformedMessageException;
import com.trading.executor.exception.UnexpectedEventTypeException;
import com.trading.executor.service.DeadLetterPublishException;
import com.trading.executor.service.DeadLetterPublisher;
import com.trading.executor.service.OrderExecutionService;
import com.trading.executor.service.SettlementOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes ORDER_PLACED messages from the orders topic.
 *
 * FAILURE HANDLING:
 * - Permanent failures (malformed JSON, missing orderId, order not found): dead-letter immediately
 * - Transient failures (broker timeout, DB error, lock contention): retry with backoff (max 4 attempts)
 * - Business outcomes (order rejected by pricing rules): no error, publish result
 * - Duplicate deliveries: detected by OrderSettlementService, no settlement occurs
 *
 * ACKNOWLEDGMENT:
 * - Only acknowledge after successful execution OR after dead-lettering
 * - Do NOT acknowledge while retrying (message will be redelivered by broker)
 *
 * @see FailureClassifier for failure classification strategy
 * @see DeadLetterPublisher for dead-letter routing
 */
@Component
public class OrderPlacedConsumer {

	private static final Logger log = LoggerFactory.getLogger(OrderPlacedConsumer.class);
	private static final String TOPIC = "orders";
	private static final int MAX_ATTEMPTS = 4;
	private static final long[] BACKOFF_MS = {0, 200, 1000, 3000}; // Waits after attempts 1, 2, 3 (growing)

	private final ObjectMapper objectMapper;
	private final OrderExecutionService executionService;
	private final FailureClassifier failureClassifier;
	private final DeadLetterPublisher deadLetterPublisher;

	public OrderPlacedConsumer(
			ObjectMapper objectMapper,
			OrderExecutionService executionService,
			FailureClassifier failureClassifier,
			DeadLetterPublisher deadLetterPublisher) {

		this.objectMapper = objectMapper;
		this.executionService = executionService;
		this.failureClassifier = failureClassifier;
		this.deadLetterPublisher = deadLetterPublisher;
	}

	@KafkaListener(
			topics = "orders",
			groupId = "trade-executor"
	)
	public void consume(
			String message,
			Acknowledgment acknowledgment,
			@Header(KafkaHeaders.RECEIVED_KEY) String key) {

		try {
			// Deserialize and validate early (before retry loop)
			OrderPlacedEvent event = deserializeAndValidate(message);

			// Execute with retry loop for transient failures
			executeWithRetry(message, key, event, acknowledgment);

		} catch (DeadLetterPublishException exception) {
			// The dead-letter write failed: leave the message unacknowledged so it is redelivered.
			throw exception;
		} catch (Exception exception) {
			// Unrecovered exception: dead-letter and acknowledge
			log.error("Unrecovered error consuming order message", exception);
			deadLetterPublisher.publishDeadLetter(
					TOPIC,
					key,
					message,
					failureClassifier.getFailureReason(exception),
					exception
			);
			acknowledgment.acknowledge();
		}
	}

	/**
	 * Deserialize and validate ORDER_PLACED event.
	 * Permanent failures throw immediately (will not be retried).
	 */
	private OrderPlacedEvent deserializeAndValidate(String message) {
		OrderPlacedEvent event;

		try {
			event = objectMapper.readValue(message, OrderPlacedEvent.class);
		} catch (Exception exception) {
			// Malformed JSON: permanent failure
			log.error("Failed to deserialize ORDER_PLACED event", exception);
			throw new MalformedMessageException("Malformed JSON in ORDER_PLACED message", exception);
		}

		// Validate event type
		if (!"ORDER_PLACED".equals(event.eventType())) {
			log.error("Unexpected event type: {}", event.eventType());
			throw new UnexpectedEventTypeException(event.eventType());
		}

		// Validate orderId (payload)
		if (event.payload() == null || event.payload().orderId() == null) {
			log.error("Missing orderId in ORDER_PLACED payload");
			throw new IllegalArgumentException("Missing orderId in ORDER_PLACED payload");
		}

		return event;
	}

	/**
	 * Execute order settlement with retry logic for transient failures.
	 * Retry up to MAX_ATTEMPTS with exponential backoff.
	 */
	private void executeWithRetry(
			String message,
			String key,
			OrderPlacedEvent event,
			Acknowledgment acknowledgment) {

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				log.info("Executing order {} (attempt {}/{})", event.payload().orderId(), attempt, MAX_ATTEMPTS);

				// Execute settlement
				SettlementOutcome outcome = executionService.execute(event.payload());

				// Success: acknowledge and return
				if (outcome.settled()) {
					log.info("Order {} settled: {}", event.payload().orderId(), outcome);
					acknowledgment.acknowledge();
					return;
				}

				// Duplicate delivery: no settlement, but still acknowledge
				log.info("ORDER_PLACED for order {} not settled now: already settled, or held until its market opens", event.payload().orderId());
				acknowledgment.acknowledge();
				return;

			} catch (Exception exception) {
				FailureType failureType = failureClassifier.classify(exception, attempt);

				if (failureType == FailureType.PERMANENT) {
					// Permanent failure: dead-letter and acknowledge
					log.warn(
							"Permanent failure executing order {} (attempt {}/{}): {}",
							event.payload().orderId(), attempt, MAX_ATTEMPTS, exception.getMessage()
					);
					deadLetterPublisher.publishDeadLetter(
							TOPIC,
							key,
							message,
							failureClassifier.getFailureReason(exception),
							exception
					);
					acknowledgment.acknowledge();
					return;
				}

				if (failureType == FailureType.TRANSIENT) {
					if (attempt == MAX_ATTEMPTS) {
						// Budget exhausted: dead-letter and acknowledge
						log.warn(
								"Exhausted retry budget for order {} after {} attempts: {}",
								event.payload().orderId(), MAX_ATTEMPTS, exception.getMessage()
						);
						deadLetterPublisher.publishDeadLetter(
								TOPIC,
								key,
								message,
								"exhausted-retry-budget",
								exception
						);
						acknowledgment.acknowledge();
						return;
					}

					// Transient failure with budget remaining: wait and retry
					long backoffMs = BACKOFF_MS[attempt - 1];
					log.warn(
							"Transient failure executing order {} (attempt {}/{}), retrying after {} ms: {}",
							event.payload().orderId(), attempt, MAX_ATTEMPTS, backoffMs, exception.getMessage()
					);

					if (backoffMs > 0) {
						try {
							Thread.sleep(backoffMs);
						} catch (InterruptedException interruptedException) {
							Thread.currentThread().interrupt();
							log.error("Interrupted during backoff for order {}", event.payload().orderId());
							deadLetterPublisher.publishDeadLetter(
									TOPIC,
									key,
									message,
									"interrupted-during-backoff",
									interruptedException
							);
							acknowledgment.acknowledge();
							return;
						}
					}

					// Continue to next attempt (loop will retry)
				}

				// BUSINESS_OUTCOME: not used here (handled in settlement logic)
			}
		}

		// Should not reach here (all code paths above return)
		log.error("Unexpected: exhausted retry loop for order without returning");
		acknowledgment.acknowledge();
	}
}
