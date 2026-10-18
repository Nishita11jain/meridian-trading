package com.trading.api.extensions.watchlists;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/** The last price each alert observed, so a crossing is told apart from a price already past the level. */
@Mapper
public interface PriceAlertStateMapper {

    int upsertLastObserved(@Param("alertId") Long alertId, @Param("price") BigDecimal price);
}
