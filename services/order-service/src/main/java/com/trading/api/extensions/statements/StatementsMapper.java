package com.trading.api.extensions.statements;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Reads what a statement shows. Read-only: nothing here writes. */
@Mapper
public interface StatementsMapper {

    /** Fills in [from, to), oldest first, with the instrument's currency. */
    List<TradeLine> findTrades(@Param("accountId") Long accountId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    /** Cash movements in [from, to), oldest first. */
    List<CashLine> findCash(@Param("accountId") Long accountId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);
}
