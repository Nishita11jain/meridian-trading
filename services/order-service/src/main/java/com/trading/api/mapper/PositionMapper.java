package com.trading.api.mapper;

import com.trading.api.mapper.row.PositionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PositionMapper {

    PositionRow findByAccountAndSymbol(@Param("accountId") Long accountId, @Param("symbol") String symbol);

    List<PositionRow> findAllHeldByAccount(@Param("accountId") Long accountId);

    int update(@Param("accountId") Long accountId, @Param("symbol") String symbol,
               @Param("quantity") int quantity,
               @Param("averageCost") java.math.BigDecimal averageCost,
               @Param("expectedVersion") int expectedVersion);

    int insert(@Param("accountId") Long accountId, @Param("symbol") String symbol,
               @Param("quantity") int quantity,
               @Param("averageCost") java.math.BigDecimal averageCost);
}
