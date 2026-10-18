package com.trading.api.fx;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only view of fx_rates for order placement; the executor's FxRatePoller writes it once a day. */
@Mapper
public interface OrderFxMapper {

    /** The most recent stored rate for a pair such as USDINR, or null when none has been fetched yet. */
    @Select("SELECT rate FROM fx_rates WHERE pair = #{pair} ORDER BY rate_date DESC LIMIT 1")
    BigDecimal findLatest(@Param("pair") String pair);

    @Select("SELECT rate_date FROM fx_rates WHERE pair = #{pair} ORDER BY rate_date DESC LIMIT 1")
    LocalDate findLatestDate(@Param("pair") String pair);
}
