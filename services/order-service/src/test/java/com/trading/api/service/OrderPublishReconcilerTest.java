package com.trading.api.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPublishReconcilerTest {

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private OrderEventPublisher orderEventPublisher;

	@Mock
	private TradingHours tradingHours;

	private OrderPublishReconciler reconciler;

	@BeforeEach
	void setUp() {
		// Open by default, so the existing cases test replay as before; closed-market cases stub it false.
		org.mockito.Mockito.lenient().when(tradingHours.status(org.mockito.ArgumentMatchers.anyString()))
				.thenReturn(new TradingHours.TradingStatus(null, true, null));
		reconciler = new OrderPublishReconciler(orderMapper, orderEventPublisher, tradingHours, 120, 300, 600, 100);
	}

	@Test
	void replaysAnOrderThatIsStillNewWithItsOriginalDetails() {
		OrderExecutionRow row = stale(UUID.randomUUID());
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of(row));

		reconciler.replayUnpublishedOrders();

		ArgumentCaptor<com.trading.domain.Order> captor = ArgumentCaptor.forClass(com.trading.domain.Order.class);
		verify(orderEventPublisher).publishOrderPlaced(captor.capture());
		assertEquals(row.getId(), captor.getValue().getId());
		assertEquals(1L, captor.getValue().getAccountId());
		assertEquals("INFY.NS", captor.getValue().getSymbol());
		assertEquals("idem-1", captor.getValue().getIdempotencyKey());
		assertEquals(row.getCreatedOn(), captor.getValue().getCreatedOn());
	}

	@Test
	void aQueuedOrderWhoseMarketIsClosedIsNotReplayedOrAlertedOn() {
		OrderExecutionRow queued = stale(UUID.randomUUID());
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of(queued));
		when(tradingHours.status(queued.getSymbol())).thenReturn(new TradingHours.TradingStatus(null, false, null));

		reconciler.replayUnpublishedOrders();

		verify(orderEventPublisher, never()).publishOrderPlaced(any());
	}

	@Test
	void doesNothingWhenNoOrderIsStuck() {
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of());

		reconciler.replayUnpublishedOrders();

		verify(orderEventPublisher, never()).publishOrderPlaced(any());
	}

	@Test
	void firstFailedPublishEndsTheRunBecauseTheBrokerIsProbablyDown() {
		OrderExecutionRow first = stale(UUID.randomUUID());
		OrderExecutionRow second = stale(UUID.randomUUID());
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of(first, second));
		doThrow(new OrderPublishException("broker down", new RuntimeException()))
				.when(orderEventPublisher).publishOrderPlaced(any());

		reconciler.replayUnpublishedOrders();

		verify(orderEventPublisher, times(1)).publishOrderPlaced(any());
	}

	@Test
	void anOrderJustReplayedIsNotReplayedAgainWithinTheCooldown() {
		OrderExecutionRow row = stale(UUID.randomUUID());
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of(row));

		reconciler.replayUnpublishedOrders();
		reconciler.replayUnpublishedOrders();

		verify(orderEventPublisher, times(1)).publishOrderPlaced(any());
	}

	@Test
	void aFailedReplayIsRetriedOnTheNextRun() {
		OrderExecutionRow row = stale(UUID.randomUUID());
		when(orderMapper.findStaleNewOrders(any(), anyInt())).thenReturn(List.of(row));
		doThrow(new OrderPublishException("broker down", new RuntimeException()))
				.doNothing()
				.when(orderEventPublisher).publishOrderPlaced(any());

		reconciler.replayUnpublishedOrders();
		reconciler.replayUnpublishedOrders();

		verify(orderEventPublisher, times(2)).publishOrderPlaced(any());
	}

	private static OrderExecutionRow stale(UUID id) {
		OrderExecutionRow row = new OrderExecutionRow();
		row.setId(id);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(OrderSide.BUY);
		row.setQuantity(10);
		row.setLimitPrice(new BigDecimal("100.00"));
		row.setStatus(OrderStatus.NEW);
		row.setIdempotencyKey("idem-1");
		row.setCreatedOn(Instant.now().minusSeconds(300));
		return row;
	}
}
