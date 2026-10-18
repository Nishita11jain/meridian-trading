package com.trading.api.mapper;

import com.trading.api.mapper.row.MarketHolidayRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MarketHolidaysMapper {

    /** Every market's holidays between two dates, inclusive, for the trading-hours rule. */
    @Select("""
            SELECT market, holiday_date AS holidayDate
            FROM market_holidays
            WHERE holiday_date BETWEEN #{from} AND #{to}
            """)
    List<MarketHolidayRow> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
