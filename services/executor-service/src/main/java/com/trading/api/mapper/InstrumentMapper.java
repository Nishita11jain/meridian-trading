package com.trading.api.mapper;

import com.trading.domain.Instrument;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InstrumentMapper {

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
