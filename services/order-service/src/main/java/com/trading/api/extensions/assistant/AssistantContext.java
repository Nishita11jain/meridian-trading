package com.trading.api.extensions.assistant;

import com.trading.api.extensions.portfolio.PricedPosition;
import com.trading.api.extensions.watchlists.MarketQuoteResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns the caller's holdings and the latest stored quotes into the words the model is allowed to see.
 *
 * <p>The rule is applied here, not left to the model: no balance, cash, market value or P&amp;L amount is
 * ever written into the context. A holding is described by its direction against cost ("above your
 * average cost"), by whether it is the largest holding, and by whether one holding dominates a
 * single-currency portfolio. A stock's own price move may be given as a percentage, because it is market
 * data rather than the user's money.
 */
final class AssistantContext {

    /** A single holding worth more than this share of a single-currency portfolio is called out as dominant. */
    static final BigDecimal DOMINANT_SHARE = new BigDecimal("0.5");

    private AssistantContext() {
    }

    static String describe(List<PricedPosition> positions, Collection<MarketQuoteResponse> quotes) {
        StringBuilder facts = new StringBuilder();
        if (positions.isEmpty()) {
            facts.append("The user holds no positions.\n");
        } else {
            facts.append("The user holds ").append(positions.size()).append(" position(s).\n");
            appendConcentration(facts, positions);
            for (PricedPosition position : positions) {
                facts.append("- ").append(position.symbol()).append(": ")
                        .append(directionAgainstCost(position))
                        .append(position.stale() ? "; its price is stale" : "")
                        .append(".\n");
            }
        }
        if (!quotes.isEmpty()) {
            facts.append("Latest stored prices (not live; each is the last batch the platform stored):\n");
            for (MarketQuoteResponse quote : quotes) {
                facts.append("- ").append(quote.symbol()).append(": ")
                        .append(moveSentence(quote));
                if (quote.quoteAsOf() != null) {
                    facts.append(", stored at ").append(quote.quoteAsOf());
                }
                facts.append(quote.stale() ? " (stale)" : "").append(".\n");
            }
        }
        return facts.toString();
    }

    static String directionAgainstCost(PricedPosition position) {
        BigDecimal pnl = position.unrealisedPnl();
        if (pnl == null) {
            return "no current price is available";
        }
        int sign = pnl.signum();
        if (sign > 0) {
            return "currently above your average cost";
        }
        if (sign < 0) {
            return "currently below your average cost";
        }
        return "currently at your average cost";
    }

    static String moveSentence(MarketQuoteResponse quote) {
        BigDecimal percent = quote.changePercent();
        if (percent == null) {
            return "no move against the previous close is stored";
        }
        BigDecimal rounded = percent.setScale(1, RoundingMode.HALF_UP);
        if (rounded.signum() == 0) {
            return "flat against the previous close";
        }
        String direction = rounded.signum() > 0 ? "up" : "down";
        return "about " + rounded.abs().toPlainString() + "% " + direction + " against the previous close";
    }

    private static void appendConcentration(StringBuilder facts, List<PricedPosition> positions) {
        Map<String, List<PricedPosition>> byCurrency = positions.stream()
                .filter(p -> p.marketValue() != null)
                .collect(Collectors.groupingBy(PricedPosition::currency));
        if (byCurrency.size() != 1) {
            // Holdings in several currencies cannot be compared by share without converting them.
            return;
        }
        List<PricedPosition> priced = byCurrency.values().iterator().next();
        BigDecimal total = priced.stream().map(PricedPosition::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return;
        }
        PricedPosition largest = priced.stream()
                .max(Comparator.comparing(PricedPosition::marketValue)).orElseThrow();
        BigDecimal share = largest.marketValue().divide(total, 4, RoundingMode.HALF_UP);
        facts.append("- ").append(largest.symbol()).append(" is the largest holding");
        if (share.compareTo(DOMINANT_SHARE) > 0) {
            facts.append(", and it makes up most of the portfolio");
        }
        facts.append(".\n");
    }

    /** Symbols in a holding list, for picking the quotes to show. Never empty for a non-empty input. */
    static List<String> symbols(List<PricedPosition> positions) {
        return positions.stream().map(PricedPosition::symbol).filter(Objects::nonNull).distinct().toList();
    }
}
