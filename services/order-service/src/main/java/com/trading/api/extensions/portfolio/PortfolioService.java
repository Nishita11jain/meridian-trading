package com.trading.api.extensions.portfolio;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.domain.Instrument;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prices an account's holdings and computes profit and loss. Read-only: it reads positions, instruments,
 * cash and the market-data read model, and writes nothing to the trading tables. Prices come from
 * market_price (maintained by the watchlist consumer from the Fauxnance feed), never a Fauxnance call
 * from here, so this module cannot spend the shared API quota or stall the order path.
 *
 * <p>Positions are reported in each instrument's own currency. The summary and the P&L are expressed in INR:
 * a dollar holding is converted at the latest stored USD/INR rate, and a holding with no usable rate is left
 * out of the totals and the summary is marked partial. Realised P&L is booked from past sells and never
 * recomputed from today's price; unrealised P&L is marketValue - costBasis and changes with every tick.
 */
@Service
public class PortfolioService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PortfolioMapper portfolioMapper;
    private final AccountMapper accountMapper;
    private final InstrumentMapper instrumentMapper;
    private final BaseCurrencyConverter converter;

    public PortfolioService(PortfolioMapper portfolioMapper, AccountMapper accountMapper,
                            InstrumentMapper instrumentMapper, BaseCurrencyConverter converter) {
        this.portfolioMapper = portfolioMapper;
        this.accountMapper = accountMapper;
        this.instrumentMapper = instrumentMapper;
        this.converter = converter;
    }

    public List<PricedPosition> positions(Long accountId, Long tokenAccountId) {
        AccountRow account = requireOwnAccount(accountId, tokenAccountId);
        List<PricedPositionRow> rows = portfolioMapper.findPricedPositions(account.getId());
        List<PricedPosition> positions = rows.stream().map(PortfolioService::toPricedPosition).toList();

        // Everything held is unpriced: the portfolio cannot be valued at all, so this is an outage.
        // A partial outage returns 200 with those positions marked stale (handled in the summary).
        if (!positions.isEmpty() && positions.stream().allMatch(p -> p.lastPrice() == null)) {
            throw new PricingUnavailableException("pricing is unavailable for every holding");
        }
        return positions;
    }

    public PortfolioSummary summary(Long accountId, Long tokenAccountId) {
        AccountRow account = requireOwnAccount(accountId, tokenAccountId);
        List<PricedPosition> positions = positions(accountId, tokenAccountId);

        BigDecimal marketValue = ZERO();
        BigDecimal costBasis = ZERO();
        BigDecimal pricedCostBasis = ZERO();
        BigDecimal unrealised = ZERO();
        boolean partial = false;
        for (PricedPosition p : positions) {
            Optional<BigDecimal> rate = converter.rateToBase(p.currency());
            if (rate.isEmpty()) {
                partial = true; // no way to express this holding in rupees, so it is left out
                continue;
            }
            costBasis = costBasis.add(BaseCurrencyConverter.convert(p.costBasis(), rate.get()));
            if (p.lastPrice() == null || p.stale()) {
                partial = true;
            }
            if (p.marketValue() != null) {
                marketValue = marketValue.add(BaseCurrencyConverter.convert(p.marketValue(), rate.get()));
                pricedCostBasis = pricedCostBasis.add(BaseCurrencyConverter.convert(p.costBasis(), rate.get()));
                unrealised = unrealised.add(BaseCurrencyConverter.convert(p.unrealisedPnl(), rate.get()));
            }
        }

        BigDecimal realised = ZERO();
        Map<String, BigDecimal> realisedBySymbol = realisedBySymbol(account.getId(), null, null);
        Map<String, String> currencyBySymbol = currencyBySymbol(positions, realisedBySymbol.keySet());
        for (Map.Entry<String, BigDecimal> entry : realisedBySymbol.entrySet()) {
            Optional<BigDecimal> rate = converter.rateToBase(currencyBySymbol.get(entry.getKey()));
            if (rate.isEmpty()) {
                partial = true;
                continue;
            }
            realised = realised.add(BaseCurrencyConverter.convert(entry.getValue(), rate.get()));
        }

        BigDecimal cash = money(account.getCashBalance().asBigDecimal());
        BigDecimal percent = pricedCostBasis.signum() == 0 ? null : percent(unrealised, pricedCostBasis);
        return new PortfolioSummary(account.getId(), BaseCurrencyConverter.BASE_CURRENCY, cash, money(marketValue),
                money(costBasis), money(unrealised), percent, money(realised), money(cash.add(marketValue)),
                positions.size(), partial, Instant.now());
    }

    /**
     * Profit and loss in rupees. from and to bound the realised figure only (the dates a sale was booked,
     * inclusive); unrealised is always as at now.
     */
    public PnlResponse pnl(Long accountId, Long tokenAccountId, LocalDate from, LocalDate to, boolean bySymbol) {
        AccountRow account = requireOwnAccount(accountId, tokenAccountId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidHistoryRangeException("from must not be after to");
        }
        List<PricedPosition> positions = positions(accountId, tokenAccountId);
        Map<String, BigDecimal> realisedBySymbol = realisedBySymbol(account.getId(), from, to);
        Map<String, String> currencyBySymbol = currencyBySymbol(positions, realisedBySymbol.keySet());

        Map<String, BigDecimal> realisedBase = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : realisedBySymbol.entrySet()) {
            converter.rateToBase(currencyBySymbol.get(entry.getKey()))
                    .ifPresent(rate -> realisedBase.put(entry.getKey(), BaseCurrencyConverter.convert(entry.getValue(), rate)));
        }
        Map<String, BigDecimal> unrealisedBase = new LinkedHashMap<>();
        for (PricedPosition p : positions) {
            if (p.unrealisedPnl() == null) {
                continue;
            }
            converter.rateToBase(p.currency())
                    .ifPresent(rate -> unrealisedBase.put(p.symbol(), BaseCurrencyConverter.convert(p.unrealisedPnl(), rate)));
        }

        BigDecimal totalRealised = realisedBase.values().stream().reduce(ZERO(), BigDecimal::add);
        BigDecimal totalUnrealised = unrealisedBase.values().stream().reduce(ZERO(), BigDecimal::add);

        List<SymbolPnl> breakdown = null;
        if (bySymbol) {
            breakdown = new ArrayList<>();
            for (String symbol : new java.util.TreeSet<>(concat(realisedBase.keySet(), unrealisedBase.keySet()))) {
                BigDecimal realised = realisedBase.getOrDefault(symbol, ZERO());
                BigDecimal unrealised = unrealisedBase.getOrDefault(symbol, ZERO());
                breakdown.add(new SymbolPnl(symbol, money(realised), money(unrealised), money(realised.add(unrealised))));
            }
        }
        return new PnlResponse(account.getId(), BaseCurrencyConverter.BASE_CURRENCY, from, to, money(totalRealised),
                money(totalUnrealised), money(totalRealised.add(totalUnrealised)), breakdown, Instant.now());
    }

    // ---------------------------------------------------------------- helpers

    private AccountRow requireOwnAccount(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            // A customer probing another account's portfolio is an access-control failure, not a lookup.
            throw new AccountNotActiveException("account is not active");
        }
        AccountRow account = accountMapper.findById(accountId);
        if (account == null) {
            throw new AccountNotFoundException("account not found");
        }
        return account;
    }

    private static PricedPosition toPricedPosition(PricedPositionRow row) {
        BigDecimal costBasis = money(row.getAverageCost().multiply(BigDecimal.valueOf(row.getQuantity())));
        BigDecimal lastPrice = row.getLastPrice();
        BigDecimal marketValue = lastPrice == null ? null : money(lastPrice.multiply(BigDecimal.valueOf(row.getQuantity())));
        BigDecimal unrealised = marketValue == null ? null : money(marketValue.subtract(costBasis));
        BigDecimal percent = unrealised == null || costBasis.signum() == 0 ? null : percent(unrealised, costBasis);
        boolean stale = lastPrice == null || Boolean.TRUE.equals(row.getStale());
        return new PricedPosition(row.getAccountId(), row.getSymbol(), row.getQuantity(), money(row.getAverageCost()), costBasis,
                lastPrice, marketValue, unrealised, percent, row.getCurrency(), row.getQuoteAsOf(), stale);
    }

    /** Weighted-average walk over filled orders: realised P&L booked at each sell against the cost then. */
    private Map<String, BigDecimal> realisedBySymbol(Long accountId, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> avg = new LinkedHashMap<>();
        Map<String, Long> qty = new LinkedHashMap<>();
        Map<String, BigDecimal> realised = new LinkedHashMap<>();
        for (FilledOrderRow order : portfolioMapper.findFilledOrders(accountId)) {
            String symbol = order.getSymbol();
            long heldQty = qty.getOrDefault(symbol, 0L);
            BigDecimal heldAvg = avg.getOrDefault(symbol, ZERO());
            if ("BUY".equals(order.getSide())) {
                long newQty = heldQty + order.getQuantity();
                BigDecimal cost = heldAvg.multiply(BigDecimal.valueOf(heldQty))
                        .add(order.getExecutedPrice().multiply(BigDecimal.valueOf(order.getQuantity())));
                avg.put(symbol, newQty == 0 ? ZERO() : cost.divide(BigDecimal.valueOf(newQty), 8, RoundingMode.HALF_UP));
                qty.put(symbol, newQty);
            } else {
                BigDecimal gain = order.getExecutedPrice().subtract(heldAvg).multiply(BigDecimal.valueOf(order.getQuantity()));
                // Every fill is walked so the average cost is right, but only sales booked in range count.
                if (inRange(order.getExecutedOn(), from, to)) {
                    realised.merge(symbol, gain, BigDecimal::add);
                }
                qty.put(symbol, heldQty - order.getQuantity());
            }
        }
        return realised;
    }

    private static boolean inRange(Instant executedOn, LocalDate from, LocalDate to) {
        if (executedOn == null) {
            return true;
        }
        LocalDate day = executedOn.atZone(ZoneOffset.UTC).toLocalDate();
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }

    private Map<String, String> currencyBySymbol(List<PricedPosition> positions, java.util.Set<String> extraSymbols) {
        Map<String, String> currency = new LinkedHashMap<>();
        for (PricedPosition p : positions) {
            currency.put(p.symbol(), p.currency());
        }
        // Symbols that were fully sold have no current position; look their currency up from the catalogue.
        for (String symbol : extraSymbols) {
            if (!currency.containsKey(symbol)) {
                Instrument instrument = instrumentMapper.findBySymbol(symbol);
                currency.put(symbol, instrument == null ? "UNKNOWN" : instrument.getCurrency());
            }
        }
        return currency;
    }

    private static java.util.Set<String> concat(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO() : value;
    }

    private static BigDecimal ZERO() {
        return BigDecimal.ZERO.setScale(2);
    }
}
