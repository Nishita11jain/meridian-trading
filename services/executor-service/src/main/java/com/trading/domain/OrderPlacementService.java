package com.trading.domain;

import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.exception.InvalidOrderParametersException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OrderPlacementService {

    private final DuplicateOrderChecker duplicateOrderChecker;

    public OrderPlacementService(DuplicateOrderChecker duplicateOrderChecker) {
        this.duplicateOrderChecker = Objects.requireNonNull(duplicateOrderChecker, "duplicateOrderChecker must not be null");
    }

    public Order placeOrder(Account account, Instrument instrument, Position position, PlaceOrderRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Rule 1
        if (account == null) {
            throw new AccountNotFoundException( "account not found");
        }

        // Rule 2
        if (!account.isActive()) {
            throw new AccountNotActiveException("account is not active");
        }

        // Rule 3
        if (instrument == null || !instrument.isTradable()) {
            throw new InstrumentNotFoundException("instrument not found or not tradable");
        }

        // Rule 4
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new InvalidOrderParametersException("quantity must be greater than zero");
        }
        long quantity = request.quantity();

        // Rule 5
        if (request.price() == null || request.price().signum() <= 0) {
            throw new InvalidOrderParametersException("price must be greater than zero");
        }
        Money price = Money.of(request.price());
        Money cost = price.multiply(quantity);

        if (request.side() == OrderSide.BUY) {
            // Rule 6
            if (!account.canAfford(cost)) {
                throw new InsufficientFundsException("insufficient funds for buy order",
                        cost.asBigDecimal(), account.getCashBalance().asBigDecimal());
            }
        } else {
            // Rule 7
            if (position == null || !position.hasSufficientHoldings((int) quantity)) {
                int held = position == null ? 0 : position.getQuantity();
                throw new InsufficientHoldingsException("insufficient holdings for sell order",
                        (int) quantity, held);
            }
        }

        // Rule 8
        if (duplicateOrderChecker.isDuplicate(request.idempotencyKey())) {
            throw new DuplicateOrderException("idempotency key already used");
        }

        // All rules passed. Cash and the position are not moved here: the order
        // stays NEW, and the Trade Executor moves cash/position exactly once,
        // at settlement. Moving them here as well would debit/credit twice for
        // every order, not just a replayed one.
        Order order = new Order(UUID.randomUUID(), account.getAccountId(), request.symbol(),
                request.side(), quantity, price, request.idempotencyKey(), Instant.now());

        return order;
    }
}
