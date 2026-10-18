package com.trading.api.extensions.strategies;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StrategiesMapper {

    /** Inserts and sets the generated id on the row. */
    int insert(StrategyRow row);

    StrategyRow findById(@Param("id") Long id);

    List<StrategyRow> findByAccount(@Param("accountId") Long accountId);

    /** Strategies the evaluator should consider for a symbol -- ACTIVE only, read fresh every quote. */
    List<StrategyRow> findActiveBySymbol(@Param("symbol") String symbol);

    int countForAccount(@Param("accountId") Long accountId);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                      @Param("disabledReason") String disabledReason);

    /** Resets the failure counter to 0 after a fill. */
    int resetFailures(@Param("id") Long id);

    /**
     * Increments the failure counter and, when it has now reached the strategy's own threshold,
     * disables it in the same statement -- so the very next evaluation (for any symbol, any
     * strategy) that reads ACTIVE rows already sees it as disabled, with no separate read-then-write
     * race between "increment" and "check the threshold".
     */
    int incrementFailureAndMaybeDisable(@Param("id") Long id, @Param("disabledReason") String disabledReason);
}
