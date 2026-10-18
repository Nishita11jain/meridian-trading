package com.trading.api.extensions.watchlists;

import com.trading.api.extensions.ProcessedEventsMapper;
import com.trading.api.extensions.advice.CandleCacheMapper;
import com.trading.api.extensions.advice.CandleCacheRow;
import com.trading.api.extensions.advice.FauxnanceCandleClient;
import com.trading.api.extensions.notifications.NotificationDelivery;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.service.TradingHours;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The market-data read model and the alert evaluation that runs on every quote. Maintains the latest
 * price per symbol and the tick history, and -- in the same transaction as the quote -- fires any
 * active alert the price has just crossed, delivering it through the notifications module.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** This module's name in the shared idempotency ledger. Not shared with the notification consumer. */
    public static final String CONSUMER_NAME = "watchlist-service";

    private static final int DEFAULT_HISTORY_LIMIT = 30;
    private static final String DEFAULT_HISTORY_RANGE = "5D";

    /** Chart ranges and their lookback span in days. */
    enum HistoryRange {
        D5("5D", 5, 5),
        M1("1M", 30, 22),
        M3("3M", 90, 66),
        M6("6M", 180, 132),
        Y1("1Y", 365, 252);

        private final String key;
        private final int fallbackSpanDays;
        private final int tradingCandles;

        HistoryRange(String key, int fallbackSpanDays, int tradingCandles) {
            this.key = key;
            this.fallbackSpanDays = fallbackSpanDays;
            this.tradingCandles = tradingCandles;
        }

        static HistoryRange parse(String key) {
            String normalized = key == null ? DEFAULT_HISTORY_RANGE : key.trim().toUpperCase();
            for (HistoryRange value : values()) {
                if (value.key.equals(normalized)) {
                    return value;
                }
            }
            throw new InvalidMarketHistoryRangeException("range must be one of 5D, 1M, 3M, 6M or 1Y");
        }
    }

    private final MarketDataMapper marketDataMapper;
    private final AlertsMapper alertsMapper;
    private final PriceAlertStateMapper priceAlertStateMapper;
    private final ProcessedEventsMapper processedEvents;
    private final NotificationDelivery notificationDelivery;
    private final InstrumentMapper instrumentMapper;
    private final TradingHours tradingHours;
    private final CandleCacheMapper candleCacheMapper;
    private final FauxnanceCandleClient candleClient;
    private final long candleCacheHours;

    public MarketDataService(MarketDataMapper marketDataMapper, AlertsMapper alertsMapper,
                             PriceAlertStateMapper priceAlertStateMapper, ProcessedEventsMapper processedEvents,
                             NotificationDelivery notificationDelivery, InstrumentMapper instrumentMapper,
                             TradingHours tradingHours,
                             CandleCacheMapper candleCacheMapper,
                             FauxnanceCandleClient candleClient,
                             @Value("${market.history.candle-cache-hours:24}") long candleCacheHours) {
        this.marketDataMapper = marketDataMapper;
        this.alertsMapper = alertsMapper;
        this.priceAlertStateMapper = priceAlertStateMapper;
        this.processedEvents = processedEvents;
        this.notificationDelivery = notificationDelivery;
        this.instrumentMapper = instrumentMapper;
        this.tradingHours = tradingHours;
        this.candleCacheMapper = candleCacheMapper;
        this.candleClient = candleClient;
        this.candleCacheHours = candleCacheHours;
    }

    public List<InstrumentResponse> getTradableInstruments() {
        var instruments = instrumentMapper.findAllTradable();
        var statuses = tradingHours.statuses(instruments.stream().map(i -> i.getSymbol()).toList());
        return instruments.stream()
                .map(i -> {
                    TradingHours.TradingStatus status = statuses.get(i.getSymbol());
                    return new InstrumentResponse(i.getSymbol(), i.getDisplayName(), i.getAssetClass(), i.getCurrency(),
                            status.market().displayName(), status.open() ? "OPEN" : "CLOSED", status.nextOpen());
                })
                .toList();
    }

    /**
     * Records a quote and evaluates alerts for its symbol, in one transaction. Returns false when the
     * event was already handled (a redelivery), so the consumer acknowledges without reprocessing.
     * Claiming, the price upsert, the history insert and any alert firing commit together. The history
     * insert skips a quote identical to the symbol's latest tick (a closed market's repeated close).
     */
    @Transactional
    public boolean ingestQuote(UUID eventId, MarketQuote quote) {
        if (processedEvents.claim(CONSUMER_NAME, eventId) == 0) {
            return false;
        }

        marketDataMapper.upsertMarketPrice(quote.symbol(), quote.price(), quote.bid(), quote.ask(),
                quote.currency(), quote.change(), quote.changePercent(), quote.previousClose(),
                quote.marketState(), quote.stale(), quote.quoteAsOf());
        marketDataMapper.insertPriceHistory(quote.symbol(), quote.price(), quote.bid(), quote.ask(), quote.quoteAsOf());

        for (AlertRow alert : alertsMapper.findActiveBySymbol(quote.symbol())) {
            evaluateAlert(eventId, alert, quote.price());
        }
        return true;
    }

    private void evaluateAlert(UUID eventId, AlertRow alert, BigDecimal price) {
        boolean above = "ABOVE".equals(alert.getDirection());
        BigDecimal target = alert.getTargetPrice();
        BigDecimal previous = alert.getLastObservedPrice();

        boolean met = above ? price.compareTo(target) >= 0 : price.compareTo(target) <= 0;
        // A crossing, not merely "past": the previous observation was on the other side of the target
        // (or there was none), so an alert does not fire again on every later quote still past the level.
        boolean crossed = met && (previous == null || (above ? previous.compareTo(target) < 0 : previous.compareTo(target) > 0));

        if (crossed && alertsMapper.markTriggered(alert.getId(), price) == 1) {
            // Deterministic id from (event, alert): a redelivered quote that re-crosses delivers once.
            UUID source = UUID.nameUUIDFromBytes((eventId + ":" + alert.getId()).getBytes(StandardCharsets.UTF_8));
            String subject = "Price alert: " + alert.getSymbol();
            String message = alert.getSymbol() + " is " + (above ? "above" : "below")
                    + " your target of " + target.toPlainString() + " (now " + price.toPlainString() + ").";
            notificationDelivery.deliver(alert.getAccountId(), source, "PRICE_ALERT_TRIGGERED", subject, message,
                    com.trading.api.extensions.notifications.NotificationReference.alert(alert.getId(), alert.getSymbol()));
            log.info("Price alert {} triggered for account {} on {}", alert.getId(), alert.getAccountId(), alert.getSymbol());
        }

        priceAlertStateMapper.upsertLastObserved(alert.getId(), price);
    }

    public List<MarketQuoteResponse> getAllQuotes() {
        return marketDataMapper.findAll().stream().map(MarketDataService::toResponse).toList();
    }

    public MarketQuoteResponse getQuote(String symbol) {
        MarketPriceRow row = marketDataMapper.findBySymbol(symbol);
        if (row == null) {
            throw new InstrumentNotFoundException("no quote for symbol " + symbol);
        }
        return toResponse(row);
    }

    public List<MarketQuoteResponse> getHistory(String symbol) {
        return marketDataMapper.findRecentHistory(symbol, DEFAULT_HISTORY_LIMIT).stream()
                .map(MarketDataService::toResponse).toList();
    }

    public List<MarketQuoteResponse> getHistory(String symbol, String rangeKey) {
        String normalizedSymbol = symbol == null ? null : symbol.trim().toUpperCase();
        HistoryRange range = HistoryRange.parse(rangeKey);
        List<MarketQuoteResponse> candleHistory = historyFromDailyCandles(normalizedSymbol, range);
        if (!candleHistory.isEmpty()) {
            return candleHistory;
        }
        // Last fallback: if no candles are available at all, keep serving local tick history.
        Instant since = Instant.now().minus(range.fallbackSpanDays, ChronoUnit.DAYS);
        return marketDataMapper.findHistorySince(normalizedSymbol, since).stream()
                .map(MarketDataService::toResponse)
                .toList();
    }

    /** Cache-aside daily candles from Fauxnance: one payload per symbol, sliced per requested range. */
    private List<MarketQuoteResponse> historyFromDailyCandles(String symbol, HistoryRange range) {
        Instant now = Instant.now();
        CandleCacheRow cached = candleCacheMapper.findBySymbol(symbol);
        String raw = null;

        if (cached != null && cached.getExpiresAt() != null && cached.getExpiresAt().isAfter(now)
                && cached.getCandlesJson() != null && !cached.getCandlesJson().isBlank()) {
            raw = cached.getCandlesJson();
        } else {
            raw = candleClient.fetchRawCandles(symbol);
            if (raw != null) {
                candleCacheMapper.upsert(symbol, "1d", null, raw, now, now.plus(candleCacheHours, ChronoUnit.HOURS));
            } else if (cached != null && cached.getCandlesJson() != null && !cached.getCandlesJson().isBlank()) {
                raw = cached.getCandlesJson();
            }
        }

        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<FauxnanceCandleClient.Candle> candles = candleClient.parseCandles(raw);
        if (candles.isEmpty()) {
            return List.of();
        }

        List<FauxnanceCandleClient.Candle> filtered = filterByRange(candles, range.tradingCandles);
        if (filtered.isEmpty()) {
            return List.of();
        }

        MarketPriceRow latest = marketDataMapper.findBySymbol(symbol);
        String currency = latest != null ? latest.getCurrency() : null;

        List<MarketQuoteResponse> out = new ArrayList<>(filtered.size());
        for (int i = filtered.size() - 1; i >= 0; i--) {
            FauxnanceCandleClient.Candle candle = filtered.get(i);
            out.add(new MarketQuoteResponse(symbol, round2(candle.close()), null, null, currency,
                    null, null, null, "unknown", false, candle.date().atStartOfDay().toInstant(ZoneOffset.UTC),
                    round2(candle.open()), round2(candle.high()), round2(candle.low())));
        }
        return out;
    }

    private static List<FauxnanceCandleClient.Candle> filterByRange(List<FauxnanceCandleClient.Candle> candles, int tradingCandles) {
        if (candles.isEmpty() || tradingCandles <= 0) {
            return List.of();
        }
        int from = Math.max(0, candles.size() - tradingCandles);
        return new ArrayList<>(candles.subList(from, candles.size()));
    }

    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    static MarketQuoteResponse toResponse(MarketPriceRow row) {
        return new MarketQuoteResponse(row.getSymbol(), row.getLastPrice(), row.getBid(), row.getAsk(),
                row.getCurrency(), row.getChange(), row.getChangePercent(), row.getPreviousClose(),
                row.getMarketState(), row.isStale(), row.getQuoteAsOf(), null, null, null);
    }

    /** The fields of a market-data QUOTE the read model needs. */
    public record MarketQuote(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, String currency,
                              BigDecimal change, BigDecimal changePercent, BigDecimal previousClose,
                              String marketState, boolean stale, Instant quoteAsOf) {
    }
}
