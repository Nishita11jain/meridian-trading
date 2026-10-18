package com.trading.executor.service;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.LedgerMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderExecutionRow;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.Account;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.Position;
import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.RejectionReason;
import com.trading.executor.domain.ExecutionDecision;
import com.trading.executor.domain.FillRule;
import com.trading.executor.domain.Quote;
import com.trading.executor.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderSettlementService {

	private static final Logger log = LoggerFactory.getLogger(OrderSettlementService.class);

	private final OrderMapper orderMapper;
	private final AccountMapper accountMapper;
	private final PositionMapper positionMapper;
	private final InstrumentMapper instrumentMapper;
	private final QuoteClient quoteClient;
	private final MarketHours marketHours;
	private final LedgerMapper ledgerMapper;

	public OrderSettlementService(
			OrderMapper orderMapper,
			AccountMapper accountMapper,
			PositionMapper positionMapper,
			InstrumentMapper instrumentMapper,
			QuoteClient quoteClient,
			MarketHours marketHours,
			LedgerMapper ledgerMapper) {

		this.orderMapper = orderMapper;
		this.accountMapper = accountMapper;
		this.positionMapper = positionMapper;
		this.instrumentMapper = instrumentMapper;
		this.quoteClient = quoteClient;
		this.marketHours = marketHours;
		this.ledgerMapper = ledgerMapper;
	}

	/**
	 * Settle an order in one atomic transaction with two guards.
	 *
	 * ATOMICITY: All three writes (status, cash, position) happen together.
	 * If any fails, none are written (transaction rolls back).
	 *
	 * GUARD 1 (Optimistic Lock on Status): The status update is guarded by
	 * a WHERE status = 'NEW' condition in orderMapper.markFilled(). This ensures
	 * only one delivery can succeed; duplicate deliveries will return 0 rows
	 * affected and publish nothing.
	 *
	 * GUARD 2 (Optimistic Lock on Account): The account cash balance update
	 * uses the version number as an optimistic lock. If another process modified
	 * the account between our read and write, the update fails with 0 rows affected,
	 * which throws OptimisticLockRetryException and triggers a retry in OrderExecutionService.
	 *
	 * WRITE ORDER (critical for correctness):
	 * 1. Status update first (guarded) — ensures exactly one delivery settles
	 * 2. Account cash update — only if status update succeeded
	 * 3. Position update — only if account update succeeded
	 *
	 * The caller (OrderExecutionService) is responsible for:
	 * - Retrying up to MAX_ATTEMPTS times on OptimisticLockRetryException
	 * - Publishing events AFTER this transaction commits (via OrderExecutionService.publish)
	 * - Acknowledging the Kafka message AFTER the event is published
	 *
	 * @param orderId the order to settle
	 * @return SettlementOutcome indicating whether settlement occurred and the result
	 * @throws OptimisticLockRetryException if account or position version mismatch (retriable)
	 */
	@Transactional
	public SettlementOutcome settle(UUID orderId) {

		OrderExecutionRow order =
				orderMapper.findExecutionById(orderId);

		if (order == null) {
			throw new OrderNotFoundException(orderId);
		}

		if (order.getStatus() != OrderStatus.NEW) {
			log.info("Duplicate ORDER_PLACED delivery ignored for settled order {}", orderId);
			return SettlementOutcome.notSettled();
		}

		// A closed market holds the order: it stays NEW, with its cash and shares reserved, and the queued
		// release settles it once its own market opens (decision log 0019). No funds decision and no quote
		// are needed until then, so a closed market costs no market-data call either.
		if (!marketHours.isOpen(order.getSymbol())) {
			log.info("Order {} held: {} is closed, it will settle when the market opens", orderId, order.getSymbol());
			return SettlementOutcome.notSettled();
		}

		Account account = toDomain(
				accountMapper.findById(order.getAccountId())
		);

		if (account == null) {
			throw new IllegalArgumentException(
					"account not found for order " + orderId
			);
		}

		Position position = toDomain(
				positionMapper.findByAccountAndSymbol(
						order.getAccountId(),
						order.getSymbol()
				)
		);

		Instrument instrument =
				instrumentMapper.findBySymbol(order.getSymbol());

		if (instrument == null || !instrument.isTradable()) {
			return reject(order, position, RejectionReason.INSTRUMENT_NOT_TRADABLE);
		}

		if (!account.isActive()) {
			return reject(order, position, RejectionReason.ACCOUNT_SUSPENDED);
		}

		// Cash is in rupees. A dollar order carries the rate locked when it was placed (null = a rupee order).
		BigDecimal fxRate = order.getFxRate();

		BigDecimal limitCost = toRupees(
				Money.of(order.getLimitPrice())
						.multiply(order.getQuantity())
						.asBigDecimal(), fxRate);

		if (order.getSide() == OrderSide.BUY
				&& !account.canAfford(Money.of(limitCost))) {

			return reject(order, position, RejectionReason.INSUFFICIENT_FUNDS);
		}

		if (order.getSide() == OrderSide.SELL
				&& (position == null
				|| !position.hasSufficientHoldings((int) order.getQuantity()))) {

			return reject(order, position, RejectionReason.INSUFFICIENT_HOLDINGS);
		}

		Quote quote =
				quoteClient.getQuote(order.getSymbol());

		ExecutionDecision decision =
				FillRule.decide(
						toPayload(order),
						quote
				);

		if (!decision.fill()) {
			return reject(order, position, decision.reason());
		}

		Money executedPrice =
				Money.of(decision.executedPrice());

		/*
		 * CRITICAL SECTION: Atomic settlement with two guards.
		 * All three writes must succeed together or none must happen.
		 *
		 * GUARD 1 (Status): Update to FILLED only if status is still NEW.
		 * Returns 0 rows if already settled by another delivery (duplicate detection).
		 * Throws exception if version mismatch on the status row itself (should not happen,
		 * as status is not versioned, but the update is guarded by the NEW status check).
		 */

		Instant executedOn = Instant.now();

		int rowsAffected =
				orderMapper.markFilled(
						order.getId(),
						executedPrice.asBigDecimal(),
						order.getQuantity(),
						executedOn
				);

		if (rowsAffected == 0) {
			// Duplicate delivery: order was already settled by another delivery.
			// No rows were updated, so account and position are not modified.
			// OrderExecutionService.publish() will not be called, so no event is published.
			return SettlementOutcome.notSettled();
		}

		// What cash moves by: the fill in the instrument's own currency, converted at the order's locked rate.
		Money tradeValue = Money.of(toRupees(
				executedPrice.multiply(order.getQuantity()).asBigDecimal(), fxRate));

		Position settledPosition;
		BigDecimal cashDelta;

		if (order.getSide() == OrderSide.BUY) {
			account.debit(tradeValue);
			settledPosition =
					position == null
							? Position.openNew(order.getAccountId(), order.getSymbol())
							: position;
			settledPosition.applyBuy((int) order.getQuantity(), executedPrice);
			persistAccountAndPosition(account, settledPosition, position == null);
			cashDelta = tradeValue.asBigDecimal().negate();
		} else {
			// A SELL with no position was already rejected above, so position is non-null here.
			position.applySell((int) order.getQuantity());
			account.credit(tradeValue);
			persistAccountAndPosition(account, position, false);
			settledPosition = position;
			cashDelta = tradeValue.asBigDecimal();
		}

		/*
		 * The execution record and the cash ledger, in the same transaction as the cash and position
		 * writes above: if any of them fails, none is kept, so the ledger always explains the balance.
		 * The amount is the exact cash delta just applied (negative for a buy, positive for a sell).
		 */
		ledgerMapper.insertTrade(order.getId(), order.getQuantity(), executedPrice.asBigDecimal(), executedOn, fxRate);
		ledgerMapper.insertCashMovement(order.getAccountId(), order.getSide().name(), cashDelta,
				order.getId().toString(), executedOn);

		return SettlementOutcome.filled(
				orderId,
				order.getAccountId(),
				order.getSymbol(),
				order.getSide(),
				order.getQuantity(),
				order.getLimitPrice(),
				executedPrice.asBigDecimal(),
				cashDelta,
				settledPosition.getQuantity(),
				settledPosition.getAverageCost().asBigDecimal(),
				executedOn
		);
	}

	/** The amount in rupees: unchanged for a rupee order, multiplied by the locked rate otherwise. */
	static BigDecimal toRupees(BigDecimal amount, BigDecimal fxRate) {
		if (fxRate == null) {
			return amount;
		}
		return amount.multiply(fxRate).setScale(2, java.math.RoundingMode.HALF_UP);
	}

	private SettlementOutcome reject(
			OrderExecutionRow order,
			Position position,
			String reason) {

		Instant rejectedOn = Instant.now();

		int rowsAffected =
				orderMapper.markRejected(
						order.getId(),
						reason,
						rejectedOn
				);

		if (rowsAffected == 0) {
			return SettlementOutcome.notSettled();
		}

		return SettlementOutcome.rejected(
				order.getId(),
				order.getAccountId(),
				order.getSymbol(),
				order.getSide(),
				order.getQuantity(),
				order.getLimitPrice(),
				reason,
				position == null ? 0 : position.getQuantity(),
				position == null
						? Money.ZERO.asBigDecimal()
						: position.getAverageCost().asBigDecimal(),
				rejectedOn
		);
	}

	private void persistAccountAndPosition(
			Account account,
			Position position,
			boolean insertPosition) {

		/*
		 * GUARD 2 & 3 (Optimistic Locks): Update cash and position.
		 * Both use version numbers to detect concurrent modifications.
		 *
		 * If the account was modified between our read and write (version mismatch),
		 * the update returns 0 rows, which throws OptimisticLockRetryException.
		 * The caller (OrderExecutionService) will retry the entire settlement.
		 *
		 * Same for position: if another process modified it, we retry.
		 *
		 * These exceptions will cause the entire transaction to roll back (no writes persist).
		 */

		int accountRowsAffected =
				accountMapper.updateCashBalanceWithVersion(
						account.getAccountId(),
						account.getCashBalance().asBigDecimal(),
						account.getVersion()
				);

		if (accountRowsAffected == 0) {
			throw new OptimisticLockRetryException(
					"account optimistic-lock race"
			);
		}

		int positionRowsAffected = insertPosition
				? positionMapper.insert(
						position.getAccountId(),
						position.getSymbol(),
						position.getQuantity(),
						position.getAverageCost().asBigDecimal()
				)
				: positionMapper.update(
						position.getAccountId(),
						position.getSymbol(),
						position.getQuantity(),
						position.getAverageCost().asBigDecimal(),
						position.getVersion()
				);

		if (positionRowsAffected == 0) {
			throw new OptimisticLockRetryException(
					"position optimistic-lock race"
			);
		}
	}

	private static OrderPlacedEvent.Payload toPayload(OrderExecutionRow order) {
		return new OrderPlacedEvent.Payload(
				order.getId(),
				order.getAccountId(),
				order.getSymbol(),
				order.getSide(),
				order.getQuantity(),
				order.getLimitPrice(),
				null,
				null
		);
	}

	private static Account toDomain(AccountRow row) {
		if (row == null) {
			return null;
		}

		return new Account(
				row.getId(),
				row.getAccountId(),
				row.getHolderName(),
				row.getCashBalance(),
				row.getStatus(),
				row.getVersion()
		);
	}

	private static Position toDomain(PositionRow row) {
		if (row == null) {
			return null;
		}

		return new Position(
				row.getAccountId(),
				row.getSymbol(),
				row.getQuantity(),
				row.getAverageCost(),
				row.getVersion()
		);
	}
}



