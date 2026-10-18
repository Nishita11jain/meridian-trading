package com.trading.executor.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.executor.exception.OrderNotFoundException;
import com.trading.executor.service.DeadLetterPublishException;
import com.trading.executor.service.DeadLetterPublisher;
import com.trading.executor.service.OptimisticLockRetryException;
import com.trading.executor.service.OrderExecutionService;
import com.trading.executor.service.OrderSettlementRetryExhaustedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the consumer with the real DefaultFailureClassifier, so the reason
 * recorded in the dead-letter header is the one an operator would actually
 * read on Monday morning, for the exceptions the consumer really produces.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacedConsumerDeadLetterReasonTest {

	private static final String KEY = "1";

	@Mock
	private OrderExecutionService executionService;

	@Mock
	private DeadLetterPublisher deadLetterPublisher;

	@Mock
	private Acknowledgment acknowledgment;

	private final OrderPlacedConsumer consumer() {
		return new OrderPlacedConsumer(
				new ObjectMapper().findAndRegisterModules(),
				executionService,
				new DefaultFailureClassifier(),
				deadLetterPublisher);
	}

	@Test
	void malformedJsonIsDeadLetteredOnFirstAttemptAsMalformedJson() {
		consumer().consume("{not json", acknowledgment, KEY);

		verify(deadLetterPublisher).publishDeadLetter(
				eq("orders"), eq(KEY), eq("{not json"), eq("malformed-json"), any());
		verify(acknowledgment).acknowledge();
		verify(executionService, never()).execute(any());
	}

	@Test
	void unknownOrderIsDeadLetteredOnFirstAttemptAsOrderNotFound() {
		UUID orderId = UUID.randomUUID();
		when(executionService.execute(any())).thenThrow(new OrderNotFoundException(orderId));

		consumer().consume(message(orderId), acknowledgment, KEY);

		verify(deadLetterPublisher).publishDeadLetter(
				eq("orders"), eq(KEY), any(), eq("order-not-found"), any());
		verify(acknowledgment).acknowledge();
		verify(executionService, times(1)).execute(any());
	}

	@Test
	void exhaustedLockBudgetIsDeadLetteredWithoutRetryingTheWholeMessageAgain() {
		UUID orderId = UUID.randomUUID();
		when(executionService.execute(any())).thenThrow(
				new OrderSettlementRetryExhaustedException("exhausted", new OptimisticLockRetryException("race")));

		consumer().consume(message(orderId), acknowledgment, KEY);

		verify(deadLetterPublisher).publishDeadLetter(
				eq("orders"), eq(KEY), any(), eq("exhausted-retry-budget"), any());
		verify(acknowledgment).acknowledge();
		verify(executionService, times(1)).execute(any());
	}

	@Test
	void failedDeadLetterWriteIsNotAcknowledged() {
		doThrow(new DeadLetterPublishException("broker down", new RuntimeException()))
				.when(deadLetterPublisher).publishDeadLetter(any(), any(), any(), any(), any());

		assertThrows(DeadLetterPublishException.class,
				() -> consumer().consume("{not json", acknowledgment, KEY));

		verify(acknowledgment, never()).acknowledge();
	}

	@Test
	void fieldsTheConsumerDoesNotRecogniseAreIgnored() {
		UUID orderId = UUID.randomUUID();
		String withExtras = message(orderId).replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"futureField\": {\"a\": 1}");
		when(executionService.execute(any())).thenReturn(com.trading.executor.service.SettlementOutcome.notSettled());

		consumer().consume(withExtras, acknowledgment, KEY);

		verify(executionService).execute(any());
		verify(deadLetterPublisher, never()).publishDeadLetter(any(), any(), any(), any(), any());
		verify(acknowledgment).acknowledge();
	}

	private static String message(UUID orderId) {
		return """
				{
				  "eventId": "b19d2c5a-8f31-4d0e-9a77-1c3e5f7a9b0d",
				  "eventType": "ORDER_PLACED",
				  "eventTime": "2026-09-28T09:14:22Z",
				  "source": "trade-api",
				  "schemaVersion": 1,
				  "payload": {
				    "orderId": "%s",
				    "accountId": 1,
				    "symbol": "AAPL",
				    "side": "BUY",
				    "quantity": 100,
				    "price": 233.00,
				    "idempotencyKey": "k-1",
				    "createdOn": "2026-09-28T09:14:22Z"
				  }
				}
				""".formatted(orderId);
	}
}
