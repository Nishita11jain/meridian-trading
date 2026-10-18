package com.trading.executor.poller;

import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fetches the two exchange rates the portfolio needs, USD to INR and INR to USD, and stores them.
 *
 * <p>Fauxnance updates its FX pairs once a day, so polling more often only spends quota on an answer that has
 * not changed. The job runs just after midnight UTC and once at startup if today's rates are missing. Both pairs
 * go in one batched call, which costs a single request.
 */
@Component
public class FxRatePoller {

    private static final Logger log = LoggerFactory.getLogger(FxRatePoller.class);

    static final String USD_INR = "FX:USDINR";
    static final String INR_USD = "FX:INRUSD";

    private final QuoteClient quoteClient;
    private final FxRateMapper fxRateMapper;

    public FxRatePoller(QuoteClient quoteClient, FxRateMapper fxRateMapper) {
        this.quoteClient = quoteClient;
        this.fxRateMapper = fxRateMapper;
    }

    @Scheduled(cron = "${fx.poll.cron:0 15 0 * * *}", zone = "UTC")
    public void pollDaily() {
        poll();
    }

    /** A fresh checkout, or an executor that was down overnight, would otherwise have no rate until the next midnight. */
    @EventListener(ApplicationReadyEvent.class)
    public void pollIfMissingAtStartup() {
        try {
            if (fxRateMapper.countToday() < 2) {
                poll();
            }
        } catch (RuntimeException exception) {
            log.warn("Could not check today's FX rates at startup: {}", exception.getMessage());
        }
    }

    /** Returns how many pairs were stored. Never throws: a failed poll leaves yesterday's rate in place. */
    int poll() {
        Map<String, Quote> quotes;
        try {
            quotes = quoteClient.getBatchQuotes(List.of(USD_INR, INR_USD));
        } catch (RuntimeException exception) {
            log.warn("FX poll failed, yesterday's rates stay in use: {}", exception.getMessage());
            return 0;
        }
        if (quotes == null) {
            return 0;
        }
        int stored = 0;
        stored += store("USDINR", quotes.get(USD_INR));
        stored += store("INRUSD", quotes.get(INR_USD));
        log.info("Stored {} FX rate(s)", stored);
        return stored;
    }

    private int store(String pair, Quote quote) {
        BigDecimal rate = midRate(quote);
        if (rate == null) {
            log.warn("No usable rate for {} in the FX poll", pair);
            return 0;
        }
        fxRateMapper.upsert(pair, rate, quote.asOf() != null ? quote.asOf() : Instant.now());
        return 1;
    }

    /** The quoted price, or the middle of bid and ask when only those are present. */
    private static BigDecimal midRate(Quote quote) {
        if (quote == null) {
            return null;
        }
        BigDecimal rate = quote.price();
        if (rate == null && quote.bid() != null && quote.ask() != null) {
            rate = quote.bid().add(quote.ask()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        }
        return rate != null && rate.signum() > 0 ? rate.setScale(6, RoundingMode.HALF_UP) : null;
    }
}
