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

    List<OrderHistoryRow> findHistoryByAccount(
        @Param("accountId") Long accountId,
        @Param("status") String status,
        @Param("from") Instant from,
        @Param("to") Instant to);

    OrderExecutionRow findExecutionById(@Param("id") UUID id);

    /** Orders still NEW, oldest first: the queued-order release checks each against its own market. */
    List<OrderExecutionRow> findNewOrdersOldestFirst(@Param("limit") int limit);

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