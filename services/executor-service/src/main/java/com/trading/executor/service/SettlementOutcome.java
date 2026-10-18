package com.trading.executor.service;

import com.trading.domain.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What settle() actually did, so the caller knows whether -- and what -- to
 * publish to trade-events once the transaction has committed. A duplicate
 * delivery, or one that lost the guarded-update race to another delivery,
 * comes back not settled: nothing is published for it.
 *
 * A settled outcome also carries everything the trade-events payload needs
 * (contracts/event-schemas/kafka-topics.md), so the publisher never has to go back to
 * Postgres: the order's symbol, side, quantity and limit price, the signed
 * cash movement, and the position after the event.
 */
public record SettlementOutcome(
		boolean settled,
		boolean filled,
		UUID orderId,
		Long accountId,
		BigDecimal executedPrice,
		String reason,
		String symbol,
		OrderSide side,
		long quantity,
		BigDecimal limitPrice,
		BigDecimal cashDelta,
		Integer positionQuantityAfter,
		BigDecimal averageCostAfter,
		Instant executedOn) {

	public static SettlementOutcome notSettled() {
		return new SettlementOutcome(
				false, false, null, null, null, null,
				null, null, 0, null, null, null, null, null);
	}

	public static SettlementOutcome filled(UUID orderId, Long accountId, BigDecimal executedPrice) {
		return new SettlementOutcome(
				true, true, orderId, accountId, executedPrice, null,
				null, null, 0, null, null, null, null, null);
	}

	public static SettlementOutcome rejected(UUID orderId, Long accountId, String reason) {
		return new SettlementOutcome(
				true, false, orderId, accountId, null, reason,
				null, null, 0, null, null, null, null, null);
	}

	public static SettlementOutcome filled(
			UUID orderId,
			Long accountId,
			String symbol,
			OrderSide side,
			long quantity,
			BigDecimal limitPrice,
			BigDecimal executedPrice,
			BigDecimal cashDelta,
			int positionQuantityAfter,
			BigDecimal averageCostAfter,
			Instant executedOn) {

		return new SettlementOutcome(
				true, true, orderId, accountId, executedPrice, null,
				symbol, side, quantity, limitPrice, cashDelta,
				positionQuantityAfter, averageCostAfter, executedOn);
	}

	public static SettlementOutcome rejected(
			UUID orderId,
			Long accountId,
			String symbol,
			OrderSide side,
			long quantity,
			BigDecimal limitPrice,
			String reason,
			int positionQuantityAfter,
			BigDecimal averageCostAfter,
			Instant executedOn) {

		return new SettlementOutcome(
				true, false, orderId, accountId, null, reason,
				symbol, side, quantity, limitPrice, BigDecimal.ZERO.setScale(2),
				positionQuantityAfter, averageCostAfter, executedOn);
	}
}
