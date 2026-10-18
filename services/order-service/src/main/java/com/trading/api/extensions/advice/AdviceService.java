package com.trading.api.extensions.advice;

import com.trading.api.extensions.watchlists.WatchlistRow;
import com.trading.api.extensions.watchlists.WatchlistsMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.exception.AccountNotActiveException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Trade Advice & Signals (Sprint 10 stretch extension). A stated BUY/SELL/HOLD view on an
 * instrument a customer holds or watches, with the reasoning behind it -- it informs a decision,
 * it never places one. A cache-aside module, not a scheduled one: nothing here calls Fauxnance
 * until a customer explicitly asks for a symbol, and a repeat ask inside the cooldown window is
 * served from {@code trade_signal} with no external call at all.
 *
 * <p>Access control follows the same shape as every other Sprint 10 extension: every method
 * compares the account in the path against the verified token's account and refuses a mismatch
 * with ACC-403, so one customer cannot trigger or read another's advice.
 */
@Service
public class AdviceService {

    private final CandleCacheMapper candleCacheMapper;
    private final TradeSignalMapper tradeSignalMapper;
    private final PositionMapper positionMapper;
    private final WatchlistsMapper watchlistsMapper;
    private final FauxnanceCandleClient candleClient;
    private final Clock clock;
    private final long cooldownHours;

    @Autowired
    public AdviceService(
            CandleCacheMapper candleCacheMapper,
            TradeSignalMapper tradeSignalMapper,
            PositionMapper positionMapper,
            WatchlistsMapper watchlistsMapper,
            FauxnanceCandleClient candleClient,
            @Value("${advice.cooldown-hours:24}") long cooldownHours) {
        this(candleCacheMapper, tradeSignalMapper, positionMapper, watchlistsMapper, candleClient,
                Clock.systemUTC(), cooldownHours);
    }

    // Test-only constructor: lets AdviceServiceTest inject a fixed Clock so "inside the cooldown"
    // vs "past it" is deterministic, same pattern as TradingHours/AssistantRateLimiter.
    AdviceService(
            CandleCacheMapper candleCacheMapper,
            TradeSignalMapper tradeSignalMapper,
            PositionMapper positionMapper,
            WatchlistsMapper watchlistsMapper,
            FauxnanceCandleClient candleClient,
            Clock clock,
            long cooldownHours) {
        this.candleCacheMapper = candleCacheMapper;
        this.tradeSignalMapper = tradeSignalMapper;
        this.positionMapper = positionMapper;
        this.watchlistsMapper = watchlistsMapper;
        this.candleClient = candleClient;
        this.clock = clock;
        this.cooldownHours = cooldownHours;
    }

    /** One card per symbol the account holds or watches; a symbol never analysed yet carries a null direction. */
    public List<SignalResponse> listSignals(Long accountId, Long tokenAccountId) {
        requireOwnership(accountId, tokenAccountId);

        List<String> symbols = eligibleSymbols(accountId);
        if (symbols.isEmpty()) {
            return List.of();
        }

        java.util.Map<String, TradeSignalRow> bySymbol = new java.util.HashMap<>();
        for (TradeSignalRow row : tradeSignalMapper.findBySymbols(symbols)) {
            bySymbol.put(row.getSymbol(), row);
        }

        return symbols.stream()
                .map(symbol -> toResponse(symbol, bySymbol.get(symbol), false, null))
                .toList();
    }

    /**
     * The explicit "Analyse now" action. Inside the cooldown, this is a cache read with no
     * external call. Past it, this calls Fauxnance, recomputes, and caches the new result; a
     * failed refresh falls back to the previous signal (marked stale) when one exists, and only
     * raises ADV-503 when there is nothing cached to fall back to at all.
     */
    public SignalResponse analyse(Long accountId, Long tokenAccountId, String symbol) {
        requireOwnership(accountId, tokenAccountId);

        String normalisedSymbol = symbol == null ? null : symbol.trim().toUpperCase();
        if (!eligibleSymbols(accountId).contains(normalisedSymbol)) {
            throw new AdviceNotEligibleException(
                    "Advice is only available for an instrument this account holds or watches: " + symbol);
        }

        TradeSignalRow cached = tradeSignalMapper.findBySymbol(normalisedSymbol);
        Instant now = clock.instant();
        if (cached != null && cached.getExpiresAt().isAfter(now)) {
            return toResponse(normalisedSymbol, cached, false, chartFromCache(normalisedSymbol));
        }

        String rawCandles = candleClient.fetchRawCandles(normalisedSymbol);
        if (rawCandles == null) {
            if (cached != null) {
                return toResponse(normalisedSymbol, cached, true, chartFromCache(normalisedSymbol));
            }
            throw new AdviceUnavailableException(
                    "Fauxnance is unavailable and no prior signal is cached for " + symbol);
        }

        Instant expiresAt = now.plus(cooldownHours, ChronoUnit.HOURS);
        candleCacheMapper.upsert(normalisedSymbol, "1d", null, rawCandles, now, expiresAt);

        List<FauxnanceCandleClient.Close> closes = candleClient.parseCloses(rawCandles);
        List<ChartPoint> chart = MovingAverageSignalCalculator.series(closes);
        Optional<MovingAverageSignalCalculator.Signal> computed = MovingAverageSignalCalculator.compute(closes);

        if (computed.isEmpty()) {
            // Not enough candle history to compute SMA50 yet. Serve the previous signal if there
            // is one (it is still the best answer on hand); otherwise there is genuinely nothing
            // to show beyond the bare price line, which the UI renders as "not enough data" rather
            // than an error.
            return cached != null
                    ? toResponse(normalisedSymbol, cached, false, chart)
                    : new SignalResponse(normalisedSymbol, null, null, null, null, null, null, false, chart, SignalResponse.DISCLAIMER);
        }

        MovingAverageSignalCalculator.Signal signal = computed.get();
        tradeSignalMapper.upsert(
                normalisedSymbol, signal.direction(), signal.strength(), signal.rationale(), now, expiresAt);

        return new SignalResponse(normalisedSymbol, signal.direction(), signal.strength(), signal.rationale(),
                signal.summary(), now, expiresAt, false, chart, SignalResponse.DISCLAIMER);
    }

    /** The chart for a symbol already served from the cache (no Fauxnance call): read, don't fetch. */
    private List<ChartPoint> chartFromCache(String symbol) {
        CandleCacheRow row = candleCacheMapper.findBySymbol(symbol);
        if (row == null) {
            return null;
        }
        return MovingAverageSignalCalculator.series(candleClient.parseCloses(row.getCandlesJson()));
    }

    private List<String> eligibleSymbols(Long accountId) {
        Set<String> symbols = new LinkedHashSet<>();
        for (PositionRow position : positionMapper.findAllHeldByAccount(accountId)) {
            if (position.getQuantity() != null && position.getQuantity() != 0) {
                symbols.add(position.getSymbol());
            }
        }
        for (WatchlistRow watchlist : watchlistsMapper.findByAccount(accountId)) {
            symbols.addAll(watchlistsMapper.findItemSymbols(watchlist.getId()));
        }
        return List.copyOf(symbols);
    }

    private SignalResponse toResponse(String symbol, TradeSignalRow row, boolean stale, List<ChartPoint> chart) {
        if (row == null) {
            return new SignalResponse(symbol, null, null, null, null, null, null, false, chart, SignalResponse.DISCLAIMER);
        }
        return new SignalResponse(symbol, row.getDirection(), row.getStrength(), row.getRationale(),
                MovingAverageSignalCalculator.summary(row.getDirection()),
                row.getComputedAt(), row.getExpiresAt(), stale, chart, SignalResponse.DISCLAIMER);
    }

    private void requireOwnership(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
    }
}
