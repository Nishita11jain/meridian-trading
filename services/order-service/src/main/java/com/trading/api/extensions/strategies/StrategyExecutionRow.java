package com.trading.api.extensions.strategies;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of strategy_execution_log. */
public class StrategyExecutionRow {

    private Long id;
    private Long strategyId;
    private Instant triggeredAt;
    private BigDecimal triggeringPrice;
    private String action;
    private String outcome;
    private String detail;
    private UUID orderId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long strategyId) { this.strategyId = strategyId; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }
    public BigDecimal getTriggeringPrice() { return triggeringPrice; }
    public void setTriggeringPrice(BigDecimal triggeringPrice) { this.triggeringPrice = triggeringPrice; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
}
