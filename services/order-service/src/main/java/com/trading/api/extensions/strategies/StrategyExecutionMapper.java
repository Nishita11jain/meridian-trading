package com.trading.api.extensions.strategies;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StrategyExecutionMapper {

    int insert(StrategyExecutionRow row);

    List<StrategyExecutionRow> findByStrategy(@Param("strategyId") Long strategyId, @Param("limit") int limit);
}
