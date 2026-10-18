package com.trading.executor.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * The queued-order release: a queued order settles on the run after its own market opens, not before, and a
 * failure on one order does not stop the others.
 */
class QueuedOrderReleaserTest {

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final MarketHours marketHours = mock(MarketHours.class);
    private final OrderExecutionService settlement = mock(OrderExecutionService.class);
    private final QueuedOrderReleaser releaser = new QueuedOrderReleaser(orderMapper, marketHours, settlement);

    private static OrderExecutionRow queued(String symbol) {
        OrderExecutionRow row = new OrderExecutionRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(1L);
        row.setSymbol(symbol);
        row.setSide(OrderSide.BUY);
        row.setQuantity(1);
        row.setLimitPrice(new BigDecimal("100.00"));
        row.setStatus(OrderStatus.NEW);
        row.setCreatedOn(Instant.now().minusSeconds(600));
        return row;
    }

    @Test
    void settlesAQueuedOrderWhoseMarketIsOpenNow() {
        OrderExecutionRow nse = queued("INFY.NS");
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of(nse));
        when(marketHours.isOpen("INFY.NS")).thenReturn(true);

        releaser.releaseOpenMarkets();

        verify(settlement).executeQueued(nse.getId());
    }

    @Test
    void leavesAQueuedOrderAloneWhileItsOwnMarketIsStillClosed() {
        OrderExecutionRow nse = queued("INFY.NS");
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of(nse));
        when(marketHours.isOpen("INFY.NS")).thenReturn(false);

        releaser.releaseOpenMarkets();

        verify(settlement, never()).executeQueued(any());
    }

    @Test
    void anOpenMarketOrderIsReleasedEvenWhenAClosedOneIsOlderInTheQueue() {
        OrderExecutionRow usClosed = queued("MSFT");
        OrderExecutionRow nseOpen = queued("INFY.NS");
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of(usClosed, nseOpen));
        when(marketHours.isOpen("MSFT")).thenReturn(false);
        when(marketHours.isOpen("INFY.NS")).thenReturn(true);

        releaser.releaseOpenMarkets();

        verify(settlement, never()).executeQueued(eq(usClosed.getId()));
        verify(settlement).executeQueued(nseOpen.getId());
    }

    @Test
    void aFailureOnOneOrderDoesNotStopTheRest() {
        OrderExecutionRow broken = queued("INFY.NS");
        OrderExecutionRow fine = queued("TCS.NS");
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of(broken, fine));
        when(marketHours.isOpen(any())).thenReturn(true);
        doThrow(new IllegalStateException("db blip")).when(settlement).executeQueued(broken.getId());

        releaser.releaseOpenMarkets();

        verify(settlement).executeQueued(fine.getId());
    }

    @Test
    void aFailedQueueReadEndsTheRunWithoutSettlingAnything() {
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenThrow(new IllegalStateException("db down"));

        releaser.releaseOpenMarkets();

        verify(settlement, never()).executeQueued(any());
    }

    @Test
    void anOrderPlacedASecondAgoIsLeftToTheConsumer() {
        OrderExecutionRow fresh = queued("INFY.NS");
        fresh.setCreatedOn(Instant.now().minusSeconds(1));
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of(fresh));
        when(marketHours.isOpen("INFY.NS")).thenReturn(true);

        releaser.releaseOpenMarkets();

        verify(settlement, never()).executeQueued(any());
    }

    @Test
    void everyRunFirstTriesAgainToPublishOutcomesThatCommittedButWereNotSent() {
        when(orderMapper.findNewOrdersOldestFirst(anyInt())).thenReturn(List.of());

        releaser.releaseOpenMarkets();

        verify(settlement).republishUnpublished();
    }
}
