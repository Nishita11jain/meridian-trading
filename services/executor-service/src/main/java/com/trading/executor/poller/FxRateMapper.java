package com.trading.executor.poller;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;

/** The fx_rates table: one row per currency pair per day, written by {@link FxRatePoller}. */
@Mapper
public interface FxRateMapper {

    /** Stores the day's rate for a pair, replacing it if the poll runs twice in one day. */
    void upsert(@Param("pair") String pair, @Param("rate") BigDecimal rate, @Param("quoteAsOf") Instant quoteAsOf);

    /** How many pairs already have a rate for today (UTC). */
    int countToday();
}
