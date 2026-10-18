package com.trading.api.extensions.portfolio;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Expresses an amount in the portfolio's base currency, INR. The platform trades in rupees and dollars, so only
 * those two convert. A currency with no rate (including a dollar amount while no USD/INR rate has been fetched)
 * returns empty, and the caller leaves that holding out of the totals and marks the summary partial.
 *
 * <p>This is for showing a portfolio in one currency, at today's mid rate. Cash is converted separately, once, at
 * placement, at the rate locked on the order (decision-log/0025).
 */
@Component
public class BaseCurrencyConverter {

    public static final String BASE_CURRENCY = "INR";

    private final FxRateMapper fxRateMapper;

    public BaseCurrencyConverter(FxRateMapper fxRateMapper) {
        this.fxRateMapper = fxRateMapper;
    }

    /** The rate that turns one unit of {@code currency} into rupees, or empty when there is none. */
    public Optional<BigDecimal> rateToBase(String currency) {
        if (BASE_CURRENCY.equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        if ("USD".equals(currency)) {
            return Optional.ofNullable(fxRateMapper.findLatest("USDINR"));
        }
        return Optional.empty();
    }

    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
