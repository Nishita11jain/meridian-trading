package com.trading.api.extensions.watchlists;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The market-data read models: the latest quote per symbol, and the append-only tick history. */
@Mapper
public interface MarketDataMapper {

    int upsertMarketPrice(
            @Param("symbol") String symbol,
            @Param("lastPrice") BigDecimal lastPrice,
            @Param("bid") BigDecimal bid,
            @Param("ask") BigDecimal ask,
            @Param("currency") String currency,
            @Param("change") BigDecimal change,
            @Param("changePercent") BigDecimal changePercent,
            @Param("previousClose") BigDecimal previousClose,
            @Param("marketState") String marketState,
            @Param("stale") boolean stale,
            @Param("quoteAsOf") Instant quoteAsOf);

    int insertPriceHistory(
            @Param("symbol") String symbol,
            @Param("price") BigDecimal price,
            @Param("bid") BigDecimal bid,
            @Param("ask") BigDecimal ask,
            @Param("quoteAsOf") Instant quoteAsOf);

    List<MarketPriceRow> findAll();

    /** When the platform last stored any price (max market_price.updated_on); null when none. */
    @org.apache.ibatis.annotations.Select("SELECT max(updated_on) FROM market_price")
    java.time.Instant findLatestUpdate();

    MarketPriceRow findBySymbol(@Param("symbol") String symbol);

    List<MarketPriceRow> findRecentHistory(@Param("symbol") String symbol, @Param("limit") int limit);

        List<MarketPriceRow> findHistorySince(@Param("symbol") String symbol, @Param("since") Instant since);
}
