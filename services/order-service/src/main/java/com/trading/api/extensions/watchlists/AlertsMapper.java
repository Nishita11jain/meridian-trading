package com.trading.api.extensions.watchlists;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AlertsMapper {

    /** Inserts and sets the generated id on the row. */
    int insertAlert(AlertRow row);

    List<AlertRow> findByAccount(@Param("accountId") Long accountId);

    AlertRow findById(@Param("id") Long id);

    /** Active alerts for a symbol, each with its last observed price (joined from price_alert_state). */
    List<AlertRow> findActiveBySymbol(@Param("symbol") String symbol);

    int countActiveByAccount(@Param("accountId") Long accountId);

    /** Flips an alert to TRIGGERED only while it is still ACTIVE; returns rows changed (0 if already fired). */
    int markTriggered(@Param("id") Long id, @Param("price") BigDecimal price);

    /** Cancels an ACTIVE alert owned by the account; returns rows changed. */
    int cancel(@Param("id") Long id, @Param("accountId") Long accountId);
}
