package com.trading.api.extensions.portfolio;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/** Read-only view of fx_rates, which the executor's FxRatePoller fills once a day. */
@Mapper
public interface FxRateMapper {

    /** The most recent stored rate for a pair such as USDINR, or null when none has been fetched yet. */
    @Select("SELECT rate FROM fx_rates WHERE pair = #{pair} ORDER BY rate_date DESC LIMIT 1")
    BigDecimal findLatest(@Param("pair") String pair);
}
