package com.trading.api.extensions.advice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One symbol's advice card. {@code direction} is null when the symbol has never been analysed yet
 * -- the UI shows "select and analyse" rather than a fabricated HOLD. {@code stale} is true only
 * when a refresh was attempted and failed and an older signal is being served in its place;
 * {@code nextAnalysisAt} is when the cooldown lets "Analyse now" call Fauxnance again.
 *
 * <p>{@code chart} is only ever populated by the Analyse-now action ({@link AdviceService#analyse});
 * the list read ({@link AdviceService#listSignals}) leaves it null, since the card list has no need
 * for the full candle history and reading it for every eligible symbol would cost a cache read each
 * for no reason until a customer actually opens one.
 *
 * <p>{@code summary} is the plain-English reading of {@code direction} (e.g. "Short-term momentum
 * has turned positive...") shown alongside {@code rationale}'s technical sentence, never in place
 * of it -- the SMA numbers stay the source of truth.
 */
public record SignalResponse(
        String symbol,
        String direction,
        BigDecimal strength,
        String rationale,
        String summary,
        Instant computedAt,
        Instant nextAnalysisAt,
        boolean stale,
        List<ChartPoint> chart,
        String disclaimer
) {
    /** Same wording on every response: what two moving averages say about past closes is not advice. */
    public static final String DISCLAIMER =
            "This reflects past price movement only, not a recommendation or a prediction. It is not financial advice.";
}
