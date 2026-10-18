package com.trading.api.mapper;

import com.trading.api.mapper.row.AccountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface AccountMapper {

    AccountRow findById(@Param("id") Long id);

    java.time.Instant findLastUpdated(@Param("id") Long id);

    /**
     * Writes the new cash balance, conditioned on the version this row held
     * when it was read -- the optimistic-lock guard. Returns the number of
     * rows affected; 0 means someone else already wrote this row first.
     * Story 5 owns the full transactional treatment around this call; this
     * mapper method is the persistence primitive that story assembles.
     */
    int updateCashBalanceWithVersion(
            @Param("accountId") Long accountId,
            @Param("newBalance") BigDecimal newBalance,
            @Param("expectedVersion") int expectedVersion);
}
