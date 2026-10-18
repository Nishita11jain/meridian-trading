package com.trading.api.mapper;

import com.trading.domain.Instrument;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InstrumentMapper {

    /**
     * The distinct currencies the catalogue trades in, so a customer can only choose a display
     * currency the platform actually quotes amounts in. Read from the owning reference-data layer
     * rather than hardcoded, so it tracks the instruments that exist.
     */
    @Select("SELECT DISTINCT currency FROM instruments ORDER BY currency")
    List<String> findDistinctCurrencies();

    /** The tradable instruments, for the watchlist and alert symbol pickers in the UI. */
    @Select("""
            SELECT symbol, instrument_name, asset_class, currency, tradable
            FROM instruments
            WHERE tradable = TRUE
            ORDER BY symbol
            """)
    @ConstructorArgs({
            @Arg(column = "symbol", javaType = String.class),
            @Arg(column = "instrument_name", javaType = String.class),
            @Arg(column = "asset_class", javaType = String.class),
            @Arg(column = "currency", javaType = String.class),
            @Arg(column = "tradable", javaType = boolean.class)
    })
    List<Instrument> findAllTradable();

    @Select("""
            SELECT symbol, instrument_name, asset_class, currency, tradable
            FROM instruments
            WHERE symbol = #{symbol}
            """)
    @ConstructorArgs({
            @Arg(column = "symbol", javaType = String.class),
            @Arg(column = "instrument_name", javaType = String.class),
            @Arg(column = "asset_class", javaType = String.class),
            @Arg(column = "currency", javaType = String.class),
            @Arg(column = "tradable", javaType = boolean.class)
    })
    Instrument findBySymbol(@Param("symbol") String symbol);
}
