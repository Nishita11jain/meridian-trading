package com.trading.api.extensions.advice;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TradeSignalMapper {

    TradeSignalRow findBySymbol(@Param("symbol") String symbol);

    List<TradeSignalRow> findBySymbols(@Param("symbols") List<String> symbols);

    int upsert(
            @Param("symbol") String symbol,
            @Param("direction") String direction,
            @Param("strength") BigDecimal strength,
            @Param("rationale") String rationale,
            @Param("computedAt") Instant computedAt,
            @Param("expiresAt") Instant expiresAt);
}
