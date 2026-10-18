package com.trading.api.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The execution record and the cash ledger (migration 007), written at settlement in the same
 * transaction as the cash and position updates, so they can never disagree with the balance.
 *
 * <ul>
 *   <li>{@code trades}: what was executed for a filled order, one row per order (unique order_id).</li>
 *   <li>{@code account_transactions}: the append-only audit trail of cash movements; a BUY is negative,
 *       a SELL positive, referenced to the order.</li>
 * </ul>
 */
@Mapper
public interface LedgerMapper {

    @Insert("""
            INSERT INTO trades (order_id, quantity, execution_price, executed_at, fx_rate)
            VALUES (#{orderId,javaType=java.util.UUID,typeHandler=com.trading.api.mapper.UuidTypeHandler},
                    #{quantity}, #{executionPrice}, #{executedAt}, #{fxRate})
            """)
    int insertTrade(@Param("orderId") UUID orderId,
                    @Param("quantity") long quantity,
                    @Param("executionPrice") BigDecimal executionPrice,
                    @Param("executedAt") Instant executedAt,
                    @Param("fxRate") BigDecimal fxRate);

    @Insert("""
            INSERT INTO account_transactions (account_id, transaction_type, amount, reference_type, reference_id, created_at)
            VALUES (#{accountId}, #{type}, #{amount}, 'ORDER', #{orderId}, #{createdAt})
            """)
    int insertCashMovement(@Param("accountId") long accountId,
                           @Param("type") String type,
                           @Param("amount") BigDecimal amount,
                           @Param("orderId") String orderId,
                           @Param("createdAt") Instant createdAt);
}
