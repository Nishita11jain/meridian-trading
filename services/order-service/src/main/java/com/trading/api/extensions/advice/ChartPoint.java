package com.trading.api.extensions.advice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day on the Analyse-now chart: the close price and, once enough history has accumulated,
 * that day's SMA20/SMA50. {@code sma20}/{@code sma50} are null for the earliest days in the
 * window, before either average has enough preceding closes to be computed.
 */
public record ChartPoint(
        LocalDate date,
        BigDecimal close,
        BigDecimal sma20,
        BigDecimal sma50
) {
}
