package com.trading.api.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.domain.PlaceOrderRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists a REJECTED order in its own, independent transaction, so the
 * audit trail survives even though the caller's enclosing @Transactional
 * method rolls back right after this same rejection. This has to be a
 * separate bean: calling a REQUIRES_NEW method on `this` from another
 * method on the same class does not actually open a new transaction --
 * Spring's proxy only intercepts calls arriving from outside the bean.
 *
 * The row carries the reason and the time it was decided, in the same
 * vocabulary the Trade Executor writes (INSUFFICIENT_FUNDS,
 * INSUFFICIENT_HOLDINGS, ...), so a refusal reads the same in the audit
 * trail and the blotter whichever service made it.
 */
@Component
public class RejectedOrderRecorder {

    /** Reason codes, shared with the executor's RejectionReason and contracts/kafka-topics.md. */
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String INSUFFICIENT_HOLDINGS = "INSUFFICIENT_HOLDINGS";

    private final OrderMapper orderMapper;

    public RejectedOrderRecorder(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** Records the refusal and returns the id of the REJECTED order row it created. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordRejection(PlaceOrderRequest request, String reason) {
        UUID id = UUID.randomUUID();
        orderMapper.insertRejected(id, request.accountId(), request.symbol(), request.side(),
                request.quantity(), request.price(), request.idempotencyKey(), reason, Instant.now());
        return id;
    }
}
