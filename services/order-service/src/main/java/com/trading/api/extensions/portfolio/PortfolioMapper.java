package com.trading.api.extensions.portfolio;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PortfolioMapper {

    /** Held positions with their instrument currency and latest quote (price/asOf/stale may be null). */
    List<PricedPositionRow> findPricedPositions(@Param("accountId") Long accountId);

    /** Filled orders for the account in execution order, for the realised-P&L walk. */
    List<FilledOrderRow> findFilledOrders(@Param("accountId") Long accountId);

    /** Filled orders with when they filled and the instrument's currency, in execution order. */
    List<HistoryFillRow> findFillsForHistory(@Param("accountId") Long accountId);

    /** Every price stored for these symbols in a window, oldest first. */
    List<PriceTickRow> findPriceTicks(@Param("symbols") List<String> symbols,
                                      @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    /** The last price stored for each of these symbols before a moment (to start the walk with). */
    List<PriceTickRow> findLastPricesBefore(@Param("symbols") List<String> symbols, @Param("before") java.time.Instant before);
}
