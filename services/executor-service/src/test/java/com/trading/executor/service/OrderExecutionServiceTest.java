package com.trading.executor.service;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

	@Mock
	private OrderSettlementService settlementService;

	@Mock
	private OrderRepository orderRepository;

	@Test
	void retriesATransientOptimisticLockRaceAndEventuallySucceeds() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();

		when(settlementService.settle(payload.orderId()))
				.thenThrow(new OptimisticLockRetryException("race-1"))
				.thenThrow(new OptimisticLockRetryException("race-2"))
				.thenReturn(SettlementOutcome.filled(payload.orderId(), payload.accountId(), new BigDecimal("100.00")));

		service.execute(payload);

		verify(settlementService, times(3)).settle(payload.orderId());
		verify(orderRepository).markFilled(argThat(outcome ->
				payload.orderId().equals(outcome.orderId())
						&& new BigDecimal("100.00").equals(outcome.executedPrice())));
	}

	@Test
	void publishFailureIsRetriedInPlaceWithoutSettlingAgain() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();
		SettlementOutcome outcome =
				SettlementOutcome.filled(payload.orderId(), payload.accountId(), new BigDecimal("100.00"));

		when(settlementService.settle(payload.orderId())).thenReturn(outcome);
		doThrow(new RuntimeException("Kafka publish timeout"))
				.doNothing()
				.when(orderRepository).markFilled(outcome);

		service.execute(payload);

		verify(settlementService, times(1)).settle(payload.orderId());
		verify(orderRepository, times(2)).markFilled(outcome);
	}

	@Test
	void publishFailureAfterRetriesThrowsAndNextDeliveryPublishesInsteadOfSettling() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();
		SettlementOutcome outcome =
				SettlementOutcome.filled(payload.orderId(), payload.accountId(), new BigDecimal("100.00"));

		when(settlementService.settle(payload.orderId())).thenReturn(outcome);
		doThrow(new RuntimeException("Kafka publish timeout"))
				.doThrow(new RuntimeException("Kafka publish timeout"))
				.doThrow(new RuntimeException("Kafka publish timeout"))
				.doNothing()
				.when(orderRepository).markFilled(outcome);

		assertThatThrownBy(() -> service.execute(payload))
				.isInstanceOf(TradeEventPublishException.class)
				.hasMessageContaining("timeout");

		// The redelivery must publish the committed fill, not settle a second time.
		service.execute(payload);

		verify(settlementService, times(1)).settle(payload.orderId());
		verify(orderRepository, times(4)).markFilled(outcome);
	}

	@Test
	void aQueuedOrderIsSettledAndItsOutcomeIsPublished() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();
		SettlementOutcome outcome =
				SettlementOutcome.filled(payload.orderId(), payload.accountId(), new BigDecimal("100.00"));
		when(settlementService.settle(payload.orderId())).thenReturn(outcome);

		service.executeQueued(payload.orderId());

		verify(orderRepository).markFilled(outcome);
	}

	@Test
	void anOutcomeThatCouldNotBeSentIsPublishedByTheNextRepublish() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();
		SettlementOutcome outcome =
				SettlementOutcome.filled(payload.orderId(), payload.accountId(), new BigDecimal("100.00"));
		when(settlementService.settle(payload.orderId())).thenReturn(outcome);
		doThrow(new RuntimeException("broker down"))
				.doThrow(new RuntimeException("broker down"))
				.doThrow(new RuntimeException("broker down"))
				.doNothing()
				.when(orderRepository).markFilled(outcome);

		try {
			service.executeQueued(payload.orderId());
		} catch (TradeEventPublishException expected) {
			// the three in-place attempts failed; the outcome stays pending
		}
		int sent = service.republishUnpublished();

		org.junit.jupiter.api.Assertions.assertEquals(1, sent);
		verify(settlementService, times(1)).settle(payload.orderId());
	}

	@Test
	void duplicateDeliveryPublishesNothing() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();

		when(settlementService.settle(payload.orderId())).thenReturn(SettlementOutcome.notSettled());

		service.execute(payload);

		verify(orderRepository, never()).markFilled(any());
		verify(orderRepository, never()).markRejected(any());
	}

	@Test
	void exhaustedRetryBudgetSurfacesAsAnError() {
		OrderExecutionService service = new OrderExecutionService(settlementService, orderRepository);
		OrderPlacedEvent.Payload payload = payload();

		when(settlementService.settle(payload.orderId()))
				.thenThrow(new OptimisticLockRetryException("race-1"))
				.thenThrow(new OptimisticLockRetryException("race-2"))
				.thenThrow(new OptimisticLockRetryException("race-3"));

		assertThatThrownBy(() -> service.execute(payload))
				.isInstanceOf(OrderSettlementRetryExhaustedException.class)
				.hasMessageContaining("exhausted optimistic-lock retry budget");

		verify(settlementService, times(3)).settle(payload.orderId());
		verify(orderRepository, never()).markFilled(any());
		verify(orderRepository, never()).markRejected(any());
	}

	private static OrderPlacedEvent.Payload payload() {
		return new OrderPlacedEvent.Payload(
				UUID.randomUUID(),
				1L,
				"INFY.NS",
				OrderSide.BUY,
				10,
				new BigDecimal("100.00"),
				"idem-key"
		);
	}
}



