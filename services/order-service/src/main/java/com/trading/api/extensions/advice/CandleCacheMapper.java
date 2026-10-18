package com.trading.api.extensions.advice;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface CandleCacheMapper {

    CandleCacheRow findBySymbol(@Param("symbol") String symbol);

    int upsert(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("currency") String currency,
            @Param("candlesJson") String candlesJson,
            @Param("fetchedAt") Instant fetchedAt,
            @Param("expiresAt") Instant expiresAt);
}
