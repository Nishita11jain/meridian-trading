package com.trading.api.extensions.portfolio;

import com.trading.api.mapper.AccountMapper;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An account's portfolio value and invested amount over time, rebuilt from data the platform already
 * stores: the filled orders (what was held, and at what average cost, at any moment) and price_history
 * (what each holding was worth then). Nothing is snapshotted and no market-data call is made; the curve
 * is recomputed per request, so it always agrees with the orders.
 *
 * <p>At each point the walk applies every fill and every stored price up to that moment, then values the
 * holdings at the latest price known then. A fill's price counts as a known price: it is what the market
 * paid at that moment, and before the platform stored prices it is the only one there is. A point that
 * uses any trade price is marked estimated. Invested is the cost basis of what was held (quantity x
 * weighted-average cost, the same walk the realised P&L uses).
 */
@Service
public class PortfolioHistoryService {

    /** The ranges the chart offers, each with its span and the spacing of its points. */
    enum Range {
        DAY("1D", Duration.ofDays(1), Duration.ofMinutes(5)),
        WEEK("1W", Duration.ofDays(7), Duration.ofMinutes(30)),
        MONTH("1M", Duration.ofDays(30), Duration.ofHours(2)),
        ALL("ALL", null, null);

        final String key;
        final Duration span;
        final Duration step;

        Range(String key, Duration span, Duration step) {
            this.key = key;
            this.span = span;
            this.step = step;
        }

        static Range parse(String key) {
            String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
            for (Range r : values()) {
                if (r.key.equals(k)) {
                    return r;
                }
            }
            throw new InvalidHistoryRangeException("range must be one of 1D, 1W, 1M or ALL");
        }
    }

    /** "All" is drawn with about this many points, however long the history. */
    private static final int ALL_POINTS = 300;
    private static final Duration MIN_STEP = Duration.ofMinutes(5);

    private final PortfolioMapper portfolioMapper;
    private final AccountMapper accountMapper;
    private final Clock clock;

    @Autowired
    public PortfolioHistoryService(PortfolioMapper portfolioMapper, AccountMapper accountMapper) {
        this(portfolioMapper, accountMapper, Clock.systemUTC());
    }

    PortfolioHistoryService(PortfolioMapper portfolioMapper, AccountMapper accountMapper, Clock clock) {
        this.portfolioMapper = portfolioMapper;
        this.accountMapper = accountMapper;
        this.clock = clock;
    }

    public List<PortfolioHistory> history(Long accountId, Long tokenAccountId, String rangeKey) {
        Range range = Range.parse(rangeKey);
        requireOwnAccount(accountId, tokenAccountId);

        List<HistoryFillRow> fills = portfolioMapper.findFillsForHistory(accountId);
        if (fills.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        Instant firstFill = fills.get(0).getExecutedOn();
        Instant from = range.span == null ? firstFill : max(now.minus(range.span), firstFill);
        Duration step = range.step != null ? range.step : allStep(firstFill, now);

        Set<String> symbols = new LinkedHashSet<>();
        Map<String, String> currencyOf = new HashMap<>();
        for (HistoryFillRow f : fills) {
            symbols.add(f.getSymbol());
            currencyOf.put(f.getSymbol(), f.getCurrency());
        }
        List<PriceTickRow> ticks = new ArrayList<>(portfolioMapper.findLastPricesBefore(new ArrayList<>(symbols), from));
        ticks.addAll(portfolioMapper.findPriceTicks(new ArrayList<>(symbols), from, now));

        // The walk: holdings and average cost per symbol, and the last known price per symbol.
        Map<String, Long> qty = new HashMap<>();
        Map<String, BigDecimal> avg = new HashMap<>();
        Map<String, BigDecimal> price = new HashMap<>();
        // Whether each symbol's known price is the account's own trade price rather than a stored one.
        Map<String, Boolean> fromTrade = new HashMap<>();
        Map<String, List<PortfolioHistory.Point>> pointsByCurrency = new LinkedHashMap<>();
        Map<String, Instant> valueSince = new HashMap<>();
        Map<String, Instant> marketSince = new HashMap<>();
        Set<String> currenciesSeen = new LinkedHashSet<>();

        int f = 0;
        int t = 0;
        for (Instant at : timeline(from, now, step)) {
            // Fills and stored prices in time order, so whichever is later sets the known price.
            while ((f < fills.size() && !fills.get(f).getExecutedOn().isAfter(at))
                    || (t < ticks.size() && !ticks.get(t).getRecordedOn().isAfter(at))) {
                boolean fillNext = f < fills.size() && !fills.get(f).getExecutedOn().isAfter(at)
                        && (t >= ticks.size() || ticks.get(t).getRecordedOn().isAfter(at)
                            || !fills.get(f).getExecutedOn().isAfter(ticks.get(t).getRecordedOn()));
                if (fillNext) {
                    HistoryFillRow fill = fills.get(f++);
                    apply(fill, qty, avg);
                    currenciesSeen.add(fill.getCurrency());
                    price.put(fill.getSymbol(), fill.getExecutedPrice());
                    fromTrade.put(fill.getSymbol(), true);
                } else {
                    PriceTickRow tick = ticks.get(t++);
                    price.put(tick.getSymbol(), tick.getPrice());
                    fromTrade.put(tick.getSymbol(), false);
                }
            }
            for (String currency : currenciesSeen) {
                BigDecimal invested = BigDecimal.ZERO;
                BigDecimal value = BigDecimal.ZERO;
                boolean priced = true;
                boolean estimated = false;
                for (Map.Entry<String, Long> holding : qty.entrySet()) {
                    if (holding.getValue() <= 0 || !currency.equals(currencyOf.get(holding.getKey()))) {
                        continue;
                    }
                    BigDecimal units = BigDecimal.valueOf(holding.getValue());
                    invested = invested.add(avg.getOrDefault(holding.getKey(), BigDecimal.ZERO).multiply(units));
                    BigDecimal last = price.get(holding.getKey());
                    if (last == null) {
                        priced = false;
                    } else {
                        value = value.add(last.multiply(units));
                        estimated |= Boolean.TRUE.equals(fromTrade.get(holding.getKey()));
                    }
                }
                if (priced) {
                    valueSince.putIfAbsent(currency, at);
                    if (!estimated) {
                        marketSince.putIfAbsent(currency, at);
                    }
                }
                pointsByCurrency.computeIfAbsent(currency, c -> new ArrayList<>())
                        .add(new PortfolioHistory.Point(at, priced ? money(value) : null, money(invested), priced && estimated));
            }
        }

        List<PortfolioHistory> result = new ArrayList<>();
        pointsByCurrency.forEach((currency, points) ->
                result.add(new PortfolioHistory(currency, range.key, valueSince.get(currency), marketSince.get(currency), points)));
        return result;
    }

    /** One fill applied to the holdings: a buy raises quantity and re-averages cost; a sell lowers quantity. */
    private static void apply(HistoryFillRow fill, Map<String, Long> qty, Map<String, BigDecimal> avg) {
        String symbol = fill.getSymbol();
        long held = qty.getOrDefault(symbol, 0L);
        if ("BUY".equals(fill.getSide())) {
            long now = held + fill.getQuantity();
            BigDecimal cost = avg.getOrDefault(symbol, BigDecimal.ZERO).multiply(BigDecimal.valueOf(held))
                    .add(fill.getExecutedPrice().multiply(BigDecimal.valueOf(fill.getQuantity())));
            avg.put(symbol, now == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(now), 8, RoundingMode.HALF_UP));
            qty.put(symbol, now);
        } else {
            long now = held - fill.getQuantity();
            qty.put(symbol, now);
            if (now <= 0) {
                avg.remove(symbol);
            }
        }
    }

    /** The moments to value the portfolio at: every step from the start, and always now. */
    static List<Instant> timeline(Instant from, Instant now, Duration step) {
        List<Instant> times = new ArrayList<>();
        for (Instant at = from; at.isBefore(now); at = at.plus(step)) {
            times.add(at);
        }
        times.add(now);
        return times;
    }

    /** For "All": spread about ALL_POINTS points over the whole history, never closer than 5 minutes. */
    static Duration allStep(Instant first, Instant now) {
        Duration step = Duration.between(first, now).dividedBy(ALL_POINTS);
        return step.compareTo(MIN_STEP) < 0 ? MIN_STEP : step;
    }

    private void requireOwnAccount(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
        if (accountMapper.findById(accountId) == null) {
            throw new AccountNotFoundException("account not found");
        }
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
