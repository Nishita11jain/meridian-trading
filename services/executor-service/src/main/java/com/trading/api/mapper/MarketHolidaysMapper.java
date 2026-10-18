package com.trading.api.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MarketHolidaysMapper {

    /** One market's holidays between two dates, inclusive (market_holidays, migration 012). */
    @Select("""
            SELECT holiday_date
            FROM market_holidays
            WHERE market = #{market} AND holiday_date BETWEEN #{from} AND #{to}
            """)
    List<LocalDate> findBetween(@Param("market") String market, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
