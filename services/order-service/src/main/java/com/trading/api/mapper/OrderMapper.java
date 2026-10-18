package com.trading.api.mapper;

import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.api.mapper.row.OrderHistoryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface OrderMapper {

    /**
     * Cash committed to queued BUY orders: the sum of quantity times limit price over NEW buys. Cash
     * stays on the account until settlement, so this is what a new buy cannot count on.
     */
    BigDecimal sumReservedCash(@Param("accountId") Long accountId);

    /** Locks the exchange rate on a freshly placed order in a foreign currency. */
    int updateFxRate(@Param("id") UUID id, @Param("fxRate") BigDecimal fxRate);

    /** Shares committed to queued SELL orders in one symbol: the sum of quantities over NEW sells. */
    long sumReservedSellQuantity(@Param("accountId") Long accountId, @Param("symbol") String symbol);

    int insert(
            @Param("id") UUID id,
            @Param("accountId") Long accountId,
            @Param("symbol") String symbol,
            @Param("side") com.trading.domain.OrderSide side,
            @Param("quantity") long quantity,
            @Param("executedPrice") BigDecimal executedPrice,
            @Param("price") BigDecimal price,
            @Param("status") com.trading.domain.OrderStatus status,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("createdOn") Instant createdOn
    );

    /**
     * Records an order the Trade API refused at placement (not enough cash, or selling more than is held)
     * as REJECTED, with the reason and the time it was decided, so the audit row reads the same as one the
     * executor rejected later. The executor's markRejected cannot be used: it only moves a NEW order.
     */
    int insertRejected(
            @Param("id") UUID id,
            @Param("accountId") Long accountId,
            @Param("symbol") String symbol,
            @Param("side") com.trading.domain.OrderSide side,
            @Param("quantity") long quantity,
            @Param("price") BigDecimal price,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("rejectionReason") String rejectionReason,
            @Param("decidedOn") Instant decidedOn
    );

    List<OrderHistoryRow> findHistoryByAccount(
        @Param("accountId") Long accountId,
        @Param("status") String status,
        @Param("from") Instant from,
        @Param("to") Instant to);

    OrderExecutionRow findExecutionById(@Param("id") UUID id);

    OrderHistoryRow findById(@Param("id") UUID id);

    /**
     * Orders still NEW that were created before {@code cutoff}, oldest first:
     * accepted, but never picked up by the executor.
     */
    List<OrderExecutionRow> findStaleNewOrders(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );

    boolean existsByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    int cancelIfNew(
            @Param("id") UUID id
    );

    int markFilled(
            @Param("id") UUID id,
            @Param("executedPrice") BigDecimal executedPrice,
            @Param("filledQuantity") long filledQuantity,
            @Param("executedOn") Instant executedOn
    );

    int markRejected(
            @Param("id") UUID id,
            @Param("rejectionReason") String rejectionReason,
            @Param("updatedOn") Instant updatedOn
    );
}