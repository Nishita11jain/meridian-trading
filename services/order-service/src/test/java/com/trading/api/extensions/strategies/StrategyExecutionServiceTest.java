package com.trading.api.extensions.strategies;

import com.trading.api.extensions.watchlists.MarketDataMapper;
import com.trading.api.extensions.watchlists.MarketPriceRow;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.PositionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The evaluation-and-firing pipeline over stand-in mappers and a stand-in order client: a genuine
 * trigger places an order and resets the failure count on success, a failed placement increments
 * it, and a risk cap stops an order before it is ever attempted (and is never counted as a
 * failure). No real HTTP, no real Kafka, no real database.
 */
class StrategyExecutionServiceTest {

    private static final String SYMBOL = "NVDA";
    private static final BigDecimal ASK = BigDecimal.valueOf(301);
    private static final BigDecimal BID = BigDecimal.valueOf(299);

    private StrategiesMapper strategiesMapper;
    private StrategyExecutionMapper executionMapper;
    private MarketDataMapper marketDataMapper;
    private PositionMapper positionMapper;
    private InternalOrderClient orderClient;
    private StrategyExecutionService service;

    @BeforeEach
    void setUp() {
        strategiesMapper = mock(StrategiesMapper.class);
        executionMapper = mock(StrategyExecutionMapper.class);
        marketDataMapper = mock(MarketDataMapper.class);
        positionMapper = mock(PositionMapper.class);
        orderClient = mock(InternalOrderClient.class);
        service = new StrategyExecutionService(strategiesMapper, executionMapper, marketDataMapper, positionMapper, orderClient);

        // 10 flat historical ticks at 100; the current tick (appended by the service) is the jump.
        when(marketDataMapper.findRecentHistory(eq(SYMBOL), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(flatHistory(10));
    }

    private static List<MarketPriceRow> flatHistory(int count) {
        List<MarketPriceRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MarketPriceRow row = new MarketPriceRow();
            row.setSymbol(SYMBOL);
            row.setLastPrice(BigDecimal.valueOf(100));
            rows.add(row);
        }
        return rows;
    }

    private static StrategyRow buyCrossover(long id, int maxSpend, int maxPosition) {
        StrategyRow row = new StrategyRow();
        row.setId(id);
        row.setAccountId(4L);
        row.setSymbol(SYMBOL);
        row.setSide("BUY");
        row.setStrategyType("CROSSOVER");
        row.setFastWindow(3);
        row.setSlowWindow(10);
        row.setOrderQuantity(20);
        row.setMaxSpend(BigDecimal.valueOf(maxSpend));
        row.setMaxPositionSize(maxPosition);
        row.setMaxConsecutiveFailures(3);
        row.setStatus("ACTIVE");
        return row;
    }

    /** Re-reads this exact row by default, so a test only needs to override it to exercise fix 3. */
    private void stubFreshRead(StrategyRow strategy) {
        when(strategiesMapper.findById(strategy.getId())).thenReturn(strategy);
    }

    @Test
    void aGenuineTriggerThatIsAcceptedResetsTheFailureCountAndLogsPlaced() {
        StrategyRow strategy = buyCrossover(1L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        stubFreshRead(strategy);
        when(orderClient.placeOrder(eq(1L), eq(4L), eq(SYMBOL), eq("BUY"), eq(20), eq(ASK), anyString()))
                .thenReturn(new InternalOrderClient.OrderOutcome(true, "NEW", "ok", "11111111-1111-1111-1111-111111111111"));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(strategiesMapper).resetFailures(1L);
        verify(strategiesMapper, never()).incrementFailureAndMaybeDisable(any(), any());
        verify(executionMapper).insert(argThat(row -> "PLACED".equals(row.getOutcome()) && row.getStrategyId().equals(1L)));
    }

    @Test
    void aBuyOrderIsPricedAtTheAskNotTheLastTradePrice() {
        StrategyRow strategy = buyCrossover(1L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        stubFreshRead(strategy);
        when(orderClient.placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new InternalOrderClient.OrderOutcome(true, "NEW", "ok", "11111111-1111-1111-1111-111111111111"));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient).placeOrder(eq(1L), eq(4L), eq(SYMBOL), eq("BUY"), eq(20), eq(ASK), anyString());
    }

    @Test
    void aSellOrderIsPricedAtTheBid() {
        StrategyRow strategy = buyCrossover(6L, 100_000, 1000);
        strategy.setSide("SELL");
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        stubFreshRead(strategy);
        when(orderClient.placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new InternalOrderClient.OrderOutcome(true, "NEW", "ok", null));

        // A downward crossing -- a SELL trigger.
        service.onQuote(SYMBOL, BigDecimal.valueOf(50), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient).placeOrder(eq(6L), eq(4L), eq(SYMBOL), eq("SELL"), eq(20), eq(BID), anyString());
    }

    @Test
    void noBidOrAskOnTheQuoteSkipsWithoutAttemptingAnOrder() {
        StrategyRow strategy = buyCrossover(1L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), null, null, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(executionMapper).insert(argThat(row -> "SKIPPED".equals(row.getOutcome())));
    }

    @Test
    void aTriggerThatWouldExceedMaxSpendIsSkippedWithoutAttemptingAnOrder() {
        // 20 units at ~301 (the ask) is well over a $100 cap.
        StrategyRow strategy = buyCrossover(2L, 100, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(strategiesMapper, never()).incrementFailureAndMaybeDisable(any(), any());
        verify(executionMapper).insert(argThat(row -> "SKIPPED".equals(row.getOutcome())));
    }

    @Test
    void aBuyTriggerThatWouldExceedMaxPositionIsSkipped() {
        StrategyRow strategy = buyCrossover(3L, 100_000, 25);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        PositionRow existing = new PositionRow();
        existing.setQuantity(10); // 10 + 20 order quantity = 30, over the 25 cap
        when(positionMapper.findByAccountAndSymbol(4L, SYMBOL)).thenReturn(existing);

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(executionMapper).insert(argThat(row -> "SKIPPED".equals(row.getOutcome())));
    }

    @Test
    void aStrategyDisabledAfterEvaluationButBeforePlacementIsNotFired() {
        StrategyRow strategy = buyCrossover(9L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        // findActiveBySymbol returned this strategy as ACTIVE for the whole-quote read, but by the
        // time of the re-check right before placement, a disable has landed.
        StrategyRow disabledByNow = buyCrossover(9L, 100_000, 1000);
        disabledByNow.setStatus("DISABLED");
        when(strategiesMapper.findById(9L)).thenReturn(disabledByNow);

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(executionMapper).insert(argThat(row -> "SKIPPED".equals(row.getOutcome())));
    }

    @Test
    void aFailedOrderPlacementIncrementsTheFailureCounterAndLogsRejected() {
        StrategyRow strategy = buyCrossover(4L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        stubFreshRead(strategy);
        when(orderClient.placeOrder(eq(4L), eq(4L), eq(SYMBOL), eq("BUY"), eq(20), eq(ASK), anyString()))
                .thenReturn(new InternalOrderClient.OrderOutcome(false, "REJECTED", "insufficient funds", null));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(strategiesMapper).incrementFailureAndMaybeDisable(eq(4L), anyString());
        verify(strategiesMapper, never()).resetFailures(any());
        verify(executionMapper).insert(argThat(row -> "REJECTED".equals(row.getOutcome())));
    }

    @Test
    void theIdempotencyKeyIsBuiltFromTheEventIdNotNanoTime() {
        StrategyRow strategy = buyCrossover(1L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));
        stubFreshRead(strategy);
        UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(orderClient.placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new InternalOrderClient.OrderOutcome(true, "NEW", "ok", null));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), eventId);

        verify(orderClient).placeOrder(eq(1L), eq(4L), eq(SYMBOL), eq("BUY"), eq(20), eq(ASK),
                eq("strat-1-22222222-2222-2222-2222-222222222222"));
    }

    @Test
    void noCrossingEventMeansNoOrderAndNoLogEntry() {
        StrategyRow strategy = buyCrossover(5L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(strategy));

        // The current tick matches the flat history: no crossover event.
        service.onQuote(SYMBOL, BigDecimal.valueOf(100), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(executionMapper, never()).insert(any());
    }

    @Test
    void aSellStrategyIgnoresAnUpwardCrossingOnTheSameSymbol() {
        StrategyRow sellStrategy = buyCrossover(6L, 100_000, 1000);
        sellStrategy.setSide("SELL");
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(sellStrategy));

        // This tick is an upward crossing -- a BUY trigger, not a SELL one.
        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(orderClient, never()).placeOrder(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(executionMapper, never()).insert(any());
    }

    @Test
    void oneStrategyThrowingDoesNotStopTheOthersFromBeingEvaluated() {
        StrategyRow broken = buyCrossover(7L, 100_000, 1000);
        broken.setFastWindow(null); // unboxes to NPE inside the evaluator
        StrategyRow healthy = buyCrossover(8L, 100_000, 1000);
        when(strategiesMapper.findActiveBySymbol(SYMBOL)).thenReturn(List.of(broken, healthy));
        stubFreshRead(healthy);
        when(orderClient.placeOrder(eq(8L), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new InternalOrderClient.OrderOutcome(true, "NEW", "ok", null));

        service.onQuote(SYMBOL, BigDecimal.valueOf(300), BID, ASK, Instant.now(), UUID.randomUUID());

        verify(executionMapper).insert(argThat(row -> row.getStrategyId().equals(8L)));
    }
}
