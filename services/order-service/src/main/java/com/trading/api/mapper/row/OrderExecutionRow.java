package com.trading.api.mapper.row;

import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class OrderExecutionRow {

	private UUID id;
	private Long accountId;
	private String symbol;
	private OrderSide side;
	private long quantity;
	private BigDecimal limitPrice;
	private OrderStatus status;

	// Only filled by findStaleNewOrders, which the publish reconciler uses to rebuild the event.
	private String idempotencyKey;
	private Instant createdOn;

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public Instant getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(Instant createdOn) {
		this.createdOn = createdOn;
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public Long getAccountId() {
		return accountId;
	}

	public void setAccountId(Long accountId) {
		this.accountId = accountId;
	}

	public String getSymbol() {
		return symbol;
	}

	public void setSymbol(String symbol) {
		this.symbol = symbol;
	}

	public OrderSide getSide() {
		return side;
	}

	public void setSide(OrderSide side) {
		this.side = side;
	}

	public long getQuantity() {
		return quantity;
	}

	public void setQuantity(long quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getLimitPrice() {
		return limitPrice;
	}

	public void setLimitPrice(BigDecimal limitPrice) {
		this.limitPrice = limitPrice;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}
}

