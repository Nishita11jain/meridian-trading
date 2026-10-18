package com.trading.executor.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.trading.executor.exception.MalformedMessageException;
import com.trading.executor.exception.OrderNotFoundException;
import com.trading.executor.exception.UnexpectedEventTypeException;
import com.trading.executor.service.OptimisticLockRetryException;
import com.trading.executor.service.OrderSettlementRetryExhaustedException;
import com.trading.executor.service.TradeEventPublishException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * Default implementation of FailureClassifier.
 *
 * Classifies exceptions as:
 * - PERMANENT: Malformed JSON, missing orderId, order not found, unexpected event type
 * - PERMANENT also covers an exhausted optimistic-lock budget: the re-reads already happened in
 *   OrderExecutionService, so the message is dead-lettered with reason exhausted-retry-budget
 * - TRANSIENT: Kafka timeouts, database connection errors, SQL errors
 * - BUSINESS_OUTCOME: Not used here (handled in settlement logic)
 */
@Component
public class DefaultFailureClassifier implements FailureClassifier {

	@Override
	public FailureType classify(Throwable exception, int attemptNumber) {

		// Permanent failures (poison messages that will never succeed)
		if (isMalformedJson(exception)) {
			return FailureType.PERMANENT; // Malformed JSON
		}
		if (exception instanceof OrderSettlementRetryExhaustedException) {
			// execute() has already re-read and retried the lock with a growing wait;
			// retrying the whole message again would multiply that budget.
			return FailureType.PERMANENT;
		}
		if (exception instanceof IllegalArgumentException) {
			return FailureType.PERMANENT; // Missing orderId or invalid data
		}
		if (exception instanceof OrderNotFoundException) {
			return FailureType.PERMANENT; // Order not found in DB
		}
		if (exception instanceof UnexpectedEventTypeException) {
			return FailureType.PERMANENT; // Wrong event type
		}

		// Transient failures (will likely succeed later)
		if (exception instanceof TradeEventPublishException) {
			return FailureType.TRANSIENT; // settled, outcome kept in memory; the next delivery publishes it
		}
		if (exception instanceof OptimisticLockRetryException) {
			return FailureType.TRANSIENT; // Lock contention, will retry
		}
		if (exception instanceof SQLException) {
			return FailureType.TRANSIENT; // DB connection lost, will recover
		}
		if (exception instanceof RuntimeException) {
			// RuntimeException from KafkaOrderRepository.publishEvent() (timeout, etc.)
			String message = exception.getMessage();
			if (message != null && (message.contains("timeout") || message.contains("Kafka"))) {
				return FailureType.TRANSIENT; // Kafka broker timeout, will retry
			}
			// Other RuntimeExceptions: treat as transient (safer than dead-lettering unknown errors)
			return FailureType.TRANSIENT;
		}

		// Unknown exceptions: treat as transient to be safe
		return FailureType.TRANSIENT;
	}

	@Override
	public String getFailureReason(Throwable exception) {

		if (isMalformedJson(exception)) {
			return "malformed-json";
		}
		if (exception instanceof OrderSettlementRetryExhaustedException) {
			return "exhausted-retry-budget";
		}
		if (exception instanceof OrderNotFoundException) {
			return "order-not-found";
		}
		if (exception instanceof UnexpectedEventTypeException) {
			return "unexpected-event-type";
		}
		if (exception instanceof IllegalArgumentException) {
			String msg = exception.getMessage();
			if (msg != null && msg.contains("idempotency")) {
				return "missing-idempotency-key";
			}
			return "missing-required-field";
		}
		if (exception instanceof TradeEventPublishException) {
			return "trade-event-publish-failed";
		}
		if (exception instanceof OptimisticLockRetryException) {
			return "exhausted-retry-budget";
		}
		if (exception instanceof SQLException) {
			return "database-error";
		}
		if (exception instanceof RuntimeException) {
			String message = exception.getMessage();
			if (message != null && message.contains("timeout")) {
				return "broker-timeout";
			}
			if (message != null && message.contains("Kafka")) {
				return "kafka-error";
			}
		}

		return "unknown-error";
	}

	private static boolean isMalformedJson(Throwable exception) {
		return exception instanceof JsonProcessingException
				|| exception instanceof MalformedMessageException
				|| exception.getCause() instanceof JsonProcessingException;
	}
}
