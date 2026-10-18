package com.trading.api.extensions.strategies;

import com.trading.api.extensions.watchlists.MarketDataMapper;
import com.trading.api.extensions.watchlists.MarketPriceRow;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.PositionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates every ACTIVE strategy on a symbol against one new quote, and fires any whose condition
 * just crossed. Driven by {@link StrategyMarketDataConsumer}; never called from any HTTP route.
 *
 * <p>Status is read fresh from the database on every single call ({@link StrategiesMapper#findActiveBySymbol})
 * -- never cached between quotes -- so disabling a strategy takes effect on its very next
 * evaluation, not at the end of some in-memory cycle. A skip (a risk cap stopping an order before
 * it is attempted) is logged but never counts as a failure; only a real order-placement attempt
 * that does not succeed moves the consecutive-failure counter, which is what the circuit breaker
 * actually watches.
 */
@Service
public class StrategyExecutionService {

    private static final Logger log = LoggerFactory.getLogger(StrategyExecutionService.class);

    /** How many preceding ticks the rolling window asks for, beyond what the widest configured strategy needs. */
    private static final int HISTORY_MARGIN = 5;

    private final StrategiesMapper strategiesMapper;
    private final StrategyExecutionMapper executionMapper;
    private final MarketDataMapper marketDataMapper;
    private final PositionMapper positionMapper;
    private final InternalOrderClient orderClient;

    public StrategyExecutionService(
            StrategiesMapper strategiesMapper,
            StrategyExecutionMapper executionMapper,
            MarketDataMapper marketDataMapper,
            PositionMapper positionMapper,
            InternalOrderClient orderClient) {
        this.strategiesMapper = strategiesMapper;
        this.executionMapper = executionMapper;
        this.marketDataMapper = marketDataMapper;
        this.positionMapper = positionMapper;
        this.orderClient = orderClient;
    }

    public void onQuote(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, Instant quoteAsOf, java.util.UUID eventId) {
        List<StrategyRow> active = strategiesMapper.findActiveBySymbol(symbol);
        for (StrategyRow strategy : active) {
            try {
                evaluateOne(strategy, symbol, price, bid, ask, eventId);
            } catch (Exception unexpected) {
                // One strategy's bug must never stop the rest of this symbol's strategies, or the
                // next symbol's quotes, from being evaluated.
                log.error("Unexpected failure evaluating strategy {}: {}", strategy.getId(), unexpected.getMessage(), unexpected);
            }
        }
    }

    private void evaluateOne(StrategyRow strategy, String symbol, BigDecimal currentPrice,
                              BigDecimal bid, BigDecimal ask, java.util.UUID eventId) {
        int requiredHistory = "CROSSOVER".equals(strategy.getStrategyType())
                ? strategy.getSlowWindow()
                : strategy.getBandPeriod();

        List<BigDecimal> window = rollingWindow(symbol, currentPrice, requiredHistory + HISTORY_MARGIN);
        Optional<StrategyEvaluator.Trigger> triggered = StrategyEvaluator.evaluate(strategy, window);
        if (triggered.isEmpty()) {
            return;
        }

        StrategyEvaluator.Trigger trigger = triggered.get();
        String action = strategy.getSide() + " " + strategy.getOrderQuantity();

        // FillRule only fills a BUY at or above the ask and a SELL at or below the bid (see
        // executor-service FillRule). Sending the last trade price as the limit means a BUY would
        // almost always land below the ask and be rejected on PRICE_NOT_MET. Send the price the
        // order can actually fill at, and cap spend against that same price.
        BigDecimal orderPrice = "BUY".equals(strategy.getSide()) ? ask : bid;
        if (orderPrice == null) {
            recordSkip(strategy, trigger, action, "No bid/ask on this quote; cannot price the order.");
            return;
        }

        BigDecimal projectedSpend = orderPrice.multiply(BigDecimal.valueOf(strategy.getOrderQuantity()));
        if (projectedSpend.compareTo(strategy.getMaxSpend()) > 0) {
            recordSkip(strategy, trigger, action, "Would spend " + projectedSpend + ", over the " + strategy.getMaxSpend() + " cap.");
            return;
        }

        if ("BUY".equals(strategy.getSide())) {
            PositionRow position = positionMapper.findByAccountAndSymbol(strategy.getAccountId(), symbol);
            int currentQuantity = position == null || position.getQuantity() == null ? 0 : position.getQuantity();
            int projectedQuantity = currentQuantity + strategy.getOrderQuantity();
            if (projectedQuantity > strategy.getMaxPositionSize()) {
                recordSkip(strategy, trigger, action, "Would hold " + projectedQuantity + " shares, over the " + strategy.getMaxPositionSize() + " cap.");
                return;
            }
        }

        // Re-read status immediately before placing: findActiveBySymbol ran once for the whole
        // quote, so a disable requested while this loop was evaluating must still be honoured
        // right at the point of firing, not just on the next quote.
        StrategyRow freshest = strategiesMapper.findById(strategy.getId());
        if (freshest == null || !"ACTIVE".equals(freshest.getStatus())) {
            recordSkip(strategy, trigger, action, "Strategy was disabled before the order could be placed.");
            return;
        }

        String idempotencyKey = "strat-" + strategy.getId() + "-" + eventId;
        InternalOrderClient.OrderOutcome outcome = orderClient.placeOrder(
                strategy.getId(), strategy.getAccountId(), symbol, strategy.getSide(),
                strategy.getOrderQuantity(), orderPrice, idempotencyKey);

        if (outcome.accepted()) {
            strategiesMapper.resetFailures(strategy.getId());
            // The order route answers NEW, not FILLED -- a fill happens later, asynchronously, in
            // the executor. Logging anything but PLACED here would claim a fill that has not
            // happened yet.
            log(strategy, trigger, action, "PLACED", trigger.rationale() + " Order " + outcome.status() + ".", outcome.orderId());
        } else {
            String reason = "Order placement failed (" + outcome.status() + "): " + outcome.message();
            strategiesMapper.incrementFailureAndMaybeDisable(strategy.getId(),
                    "Auto-disabled after " + strategy.getMaxConsecutiveFailures() + " consecutive order failures.");
            log(strategy, trigger, action, "REJECTED", reason, outcome.orderId());
        }
    }

    private void recordSkip(StrategyRow strategy, StrategyEvaluator.Trigger trigger, String action, String reason) {
        log.info("Strategy {} skipped a trigger: {}", strategy.getId(), reason);
        log(strategy, trigger, action, "SKIPPED", reason, null);
    }

    private void log(StrategyRow strategy, StrategyEvaluator.Trigger trigger, String action,
                      String outcome, String detail, String orderId) {
        StrategyExecutionRow row = new StrategyExecutionRow();
        row.setStrategyId(strategy.getId());
        row.setTriggeringPrice(trigger.triggerPrice());
        row.setAction(action);
        row.setOutcome(outcome);
        row.setDetail(detail);
        row.setOrderId(orderId == null ? null : java.util.UUID.fromString(orderId));
        executionMapper.insert(row);
    }

    /** Oldest to newest: up to {@code limit} preceding closes from price_history, plus the just-arrived quote. */
    private List<BigDecimal> rollingWindow(String symbol, BigDecimal currentPrice, int limit) {
        List<MarketPriceRow> history = marketDataMapper.findRecentHistory(symbol, limit);
        List<BigDecimal> prices = new ArrayList<>(history.size() + 1);
        for (MarketPriceRow row : history) {
            prices.add(row.getLastPrice());
        }
        Collections.reverse(prices);
        prices.add(currentPrice);
        return prices;
    }
}
