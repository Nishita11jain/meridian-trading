package com.trading.api.service;

import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.OrderResponse;
import com.trading.api.exception.OrderNotFoundException;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderHistoryRow;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.Account;
import com.trading.domain.Instrument;
import com.trading.domain.Order;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import com.trading.domain.Position;
import com.trading.domain.Money;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.api.fx.OrderFxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final AccountMapper accountMapper;
    private final InstrumentMapper instrumentMapper;
    private final PositionMapper positionMapper;
    private final OrderMapper orderMapper;
    private final OrderPlacementService orderPlacementService;
    private final OrderEventPublisher orderEventPublisher;
    private final RejectedOrderRecorder rejectedOrderRecorder;
    private final TradingHours tradingHours;
    private final OrderFxService fxService;

    public OrderServiceImpl(
            AccountMapper accountMapper,
            InstrumentMapper instrumentMapper,
            PositionMapper positionMapper,
            OrderMapper orderMapper,
            OrderPlacementService orderPlacementService,
            OrderEventPublisher orderEventPublisher,
            RejectedOrderRecorder rejectedOrderRecorder,
            TradingHours tradingHours,
            OrderFxService fxService) {

        this.accountMapper = accountMapper;
        this.instrumentMapper = instrumentMapper;
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
        this.orderPlacementService = orderPlacementService;
        this.orderEventPublisher = orderEventPublisher;
        this.rejectedOrderRecorder = rejectedOrderRecorder;
        this.tradingHours = tradingHours;
        this.fxService = fxService;
    }

    @Override
    @Transactional
    public OrderResponse placeOrder(
            PlaceOrderRequest request,
            Long tokenAccountId) {

        /*
         * Ownership first, existence second. Answering "not found" for an
         * account that does not exist but "forbidden" for one that belongs to
         * somebody else tells a caller which account numbers are real. A
         * request for any account that is not the token's own is refused
         * the same way, whether or not that account exists.
         */
        ensureAccountAccess(
                request.accountId(),
                tokenAccountId
        );

        // Locked, so two orders placed at the same moment see each other's reservations.
        AccountRow accountRow =
                accountMapper.findByIdForUpdate(request.accountId());

        if (accountRow == null) {
            throw new AccountNotFoundException(
                    "account not found"
            );
        }

        Account account =
                toDomain(accountRow);

        Instrument instrument =
                instrumentMapper.findBySymbol(
                        request.symbol()
                );

        /*
         * A closed market no longer refuses the order. It is stored as NEW and queued: the Trade Executor
         * settles it when its own market opens (decision log 0019). Cash and shares are reserved for it
         * from now, so the checks below run against what is left after the queued orders.
         */
        PositionRow positionRow =
                positionMapper.findByAccountAndSymbol(
                        request.accountId(),
                        request.symbol()
                );

        Position position =
                toDomain(positionRow);

        BigDecimal reservedCash =
                orderMapper.sumReservedCash(request.accountId());
        long reservedShares =
                orderMapper.sumReservedSellQuantity(request.accountId(), request.symbol());

        account = withAvailableCash(account, reservedCash);
        position = withAvailableShares(position, reservedShares);

        Order order;
        // Rupees per unit of the instrument's currency, locked on the order; null for a rupee instrument.
        BigDecimal fxRate = null;

        try {

            order =
                    orderPlacementService.placeOrder(
                            account,
                            instrument,
                            position,
                            request
                    );

            // The domain checked the cost in the instrument's own currency. Cash is in rupees, so a dollar buy
            // is checked again at the rate the order will be locked at (decision log 0020).
            fxRate = fxService.lockedRate(instrument.getCurrency(), request.side());
            if (fxRate != null && request.side() == OrderSide.BUY) {
                Money rupeeCost = Money.of(request.price()
                        .multiply(BigDecimal.valueOf(request.quantity()))
                        .multiply(fxRate)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
                if (!account.canAfford(rupeeCost)) {
                    throw new InsufficientFundsException("insufficient funds for buy order",
                            rupeeCost.asBigDecimal(), account.getCashBalance().asBigDecimal());
                }
            }

        } catch (InsufficientFundsException |
                 InsufficientHoldingsException exception) {

            String reason = exception instanceof InsufficientFundsException
                    ? RejectedOrderRecorder.INSUFFICIENT_FUNDS
                    : RejectedOrderRecorder.INSUFFICIENT_HOLDINGS;

            try {
                UUID rejectedId = rejectedOrderRecorder
                        .recordRejection(request, reason);

                // The REJECTED row has committed in its own transaction, so it is safe to announce
                // now -- and it has to be now: this method's own transaction is about to roll back,
                // so an after-commit hook on it would never run. A broker failure must not turn the
                // customer's 400/409 into a 500, so it is logged, not thrown; the row is the record.
                try {
                    orderEventPublisher.publishOrderRejected(rejectedId, request, reason, position);
                } catch (RuntimeException publishFailure) {
                    log.warn(
                            "Recorded rejection {} ({}) but could not publish ORDER_REJECTED: {}",
                            rejectedId, reason, publishFailure.getMessage()
                    );
                }
            } catch (DuplicateKeyException duplicate) {
                // The same request was already rejected and recorded under this
                // idempotency key. The audit row and its event exist, so there is nothing more
                // to record or announce, and the caller still gets the original 400/409.
                log.info(
                        "Rejection for idempotency key {} is already recorded",
                        request.idempotencyKey()
                );
            }

            throw exception;
        }

        /*
         * IMPORTANT:
         *
         * Do NOT fill the order here, and do NOT move cash or the
         * position here either. The order remains NEW. Trade Executor
         * will later decide whether it becomes FILLED or REJECTED, and
         * moves cash/position exactly once, at settlement.
         */

        persist(order);
        if (fxRate != null) {
            orderMapper.updateFxRate(order.getId(), fxRate);
        }

        /*
         * Publish only after successful DB commit.
         */
        registerOrderPlacedAfterCommit(order);

        return toResponse(
                order,
                "Order accepted"
        );
    }

    private void persist(Order order) {

        try {

            orderMapper.insert(
                    order.getId(),
                    order.getAccountId(),
                    order.getSymbol(),
                    order.getSide(),
                    order.getQuantity(),
                    null,
                    order.getLimitPrice().asBigDecimal(),
                    order.getStatus(),
                    order.getIdempotencyKey(),
                    order.getCreatedOn()
            );

        } catch (DuplicateKeyException exception) {

            throw new DuplicateOrderException(
                    "idempotency key already used"
            );
        }
    }

    private void registerOrderPlacedAfterCommit(
            Order order) {

        publishAfterCommit(
                () -> orderEventPublisher
                        .publishOrderPlaced(order)
        );
    }

    private void publishAfterCommit(Runnable publication) {

        if (!TransactionSynchronizationManager
                .isActualTransactionActive()) {

            runPublication(publication);

            return;
        }

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {

                                runPublication(publication);
                            }
                        }
                );
    }

    /**
     * The database change has already committed when this runs, so a broker
     * failure must not turn into an error for the client. An order left at NEW
     * is replayed by OrderPublishReconciler.
     */
    private void runPublication(Runnable publication) {

        try {
            publication.run();
        } catch (RuntimeException exception) {
            log.error(
                    "Order change committed but its event was not published; "
                            + "a NEW order is replayed by the reconciler",
                    exception
            );
        }
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(
            UUID orderId,
            Long tokenAccountId) {

        OrderHistoryRow existingRow =
                orderMapper.findById(orderId);

        if (existingRow == null) {
            throw new OrderNotFoundException("Order not found");
        }

        OrderHistoryEntry existing =
                toDomain(existingRow);

        if (tokenAccountId != null
                && !existing.accountId()
                .equals(tokenAccountId)) {

            throw new AccountNotActiveException(
                    "account is not active"
            );
        }

        if (existing.status() != OrderStatus.NEW) {

            throw new DuplicateOrderException(
                    "Order is not cancellable"
            );
        }

        int rowsAffected =
                orderMapper.cancelIfNew(orderId);

        if (rowsAffected == 0) {

            throw new DuplicateOrderException(
                    "Order is not cancellable"
            );
        }

        Order cancelledOrder = new Order(
                orderId,
                existing.accountId(),
                existing.symbol(),
                existing.side(),
                existing.quantity(),
                Money.of(existing.price()),
                existing.idempotencyKey(),
                existing.createdOn()
        );

        Position heldPosition =
                toDomain(
                        positionMapper.findByAccountAndSymbol(
                                existing.accountId(),
                                existing.symbol()
                        )
                );

        /*
         * Publish only after the cancel has committed, like ORDER_PLACED: an
         * event for a cancel that then rolled back cannot be undone.
         */
        publishAfterCommit(
                () -> orderEventPublisher.publishOrderCancelled(
                        cancelledOrder,
                        heldPosition
                )
        );

        return new OrderResponse(
                existing.orderId(),
                OrderStatus.CANCELLED,
                "Order cancelled",
                existing.symbol(),
                existing.side(),
                existing.quantity(),
                existing.price()
        );
    }

    @Override
    public List<OrderHistoryEntry> getOrderHistory(
            Long accountId,
            Long tokenAccountId,
            OrderStatus status,
            Instant from,
            Instant to) {

        // Ownership before existence, so an unknown account and someone else's
        // account cannot be told apart by the response.
        ensureAccountAccess(
                accountId,
                tokenAccountId
        );

        if (accountMapper.findById(accountId) == null) {

            throw new AccountNotFoundException(
                    "account not found"
            );
        }

        String statusFilter =
                status == null
                        ? null
                        : status.name();

        return orderMapper
                .findHistoryByAccount(
                        accountId,
                        statusFilter,
                        from,
                        to
                )
                .stream()
                .map(OrderServiceImpl::toDomain)
                .toList();
    }

    private OrderResponse toResponse(
            Order order,
            String message) {

        return new OrderResponse(
                "ORD-" + order.getId(),
                order.getStatus(),
                message,
                order.getSymbol(),
                order.getSide(),
                (int) order.getQuantity(),
                order.getLimitPrice().asBigDecimal()
        );
    }

    /**
     * The same account with the cash committed to queued buys taken away, so the placement rule checks what
     * this order can really use. Not stored: it only shapes the check.
     */
    private static Account withAvailableCash(Account account, BigDecimal reservedCash) {
        if (account == null) {
            return null;
        }
        BigDecimal reserved = reservedCash == null ? BigDecimal.ZERO : reservedCash;
        return new Account(
                account.getAccountId(),
                account.getAccountReference(),
                account.getHolderName(),
                account.getCashBalance().subtract(Money.of(reserved)),
                account.getStatus(),
                account.getVersion()
        );
    }

    /** The same position with the shares committed to queued sells taken away. Not stored. */
    private static Position withAvailableShares(Position position, long reservedShares) {
        if (position == null || reservedShares == 0) {
            return position;
        }
        return new Position(
                position.getAccountId(),
                position.getSymbol(),
                (int) (position.getQuantity() - reservedShares),
                position.getAverageCost(),
                position.getVersion()
        );
    }

    private static Account toDomain(
            AccountRow account) {

        if (account == null) {
            return null;
        }

        return new Account(
                account.getId(),
                account.getAccountId(),
                account.getHolderName(),
                account.getCashBalance(),
                account.getStatus(),
                account.getVersion()
        );
    }

    private static Position toDomain(
            PositionRow position) {

        if (position == null) {
            return null;
        }

        return new Position(
                position.getAccountId(),
                position.getSymbol(),
                position.getQuantity(),
                position.getAverageCost(),
                position.getVersion()
        );
    }

    @Override
    public java.util.Optional<OrderHistoryEntry> findOwnOrder(UUID orderId, long accountId) {
        OrderHistoryRow row = orderMapper.findById(orderId);
        if (row == null || row.getAccountId() == null || row.getAccountId() != accountId) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(toDomain(row));
    }

    private static OrderHistoryEntry toDomain(
            OrderHistoryRow row) {

        return new OrderHistoryEntry(
                row.getOrderId(),
                row.getAccountId(),
                row.getSymbol(),
                row.getSide(),
                row.getQuantity(),
                row.getPrice(),
                row.getExecutedPrice(),
                row.getStatus(),
                row.getIdempotencyKey(),
                row.getCreatedOn(),
                row.getRejectionReason()
        );
    }

    private static void ensureAccountAccess(
            Long accountId,
            Long tokenAccountId) {

        if (tokenAccountId != null
                && !accountId.equals(tokenAccountId)) {

            throw new AccountNotActiveException(
                    "account is not active"
            );
        }
    }
}