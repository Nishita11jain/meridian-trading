package com.trading.api.extensions.watchlists;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * When prices were last stored and when the next batch is due. The poller (in the executor) publishes a
 * batch every poll.interval-seconds; this reads the same setting (POLL_INTERVAL_SECONDS, default 300),
 * so the two agree. Reads our own table only; it never calls the market-data provider.
 */
@Service
public class MarketFreshnessService {

    private final MarketDataMapper marketDataMapper;
    private final int pollIntervalSeconds;

    public MarketFreshnessService(MarketDataMapper marketDataMapper,
                                  @Value("${poll.interval-seconds:300}") int pollIntervalSeconds) {
        this.marketDataMapper = marketDataMapper;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public MarketFreshnessResponse freshness() {
        Instant last = marketDataMapper.findLatestUpdate();
        Instant next = last == null ? null : last.plusSeconds(pollIntervalSeconds);
        return new MarketFreshnessResponse(last, next, pollIntervalSeconds);
    }
}
