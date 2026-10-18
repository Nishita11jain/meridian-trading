package com.trading.api.extensions.watchlists;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of price_alerts. lastObservedPrice is populated only by findActiveBySymbol (a join on
 * price_alert_state); the other queries leave it null, as the inbox and list views do not need it.
 */
public class AlertRow {
    private Long id;
    private Long accountId;
    private String symbol;
    private String direction;
    private BigDecimal targetPrice;
    private String status;
    private Instant createdOn;
    private Instant triggeredOn;
    private BigDecimal triggeredPrice;
    private BigDecimal lastObservedPrice;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal targetPrice) { this.targetPrice = targetPrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }
    public Instant getTriggeredOn() { return triggeredOn; }
    public void setTriggeredOn(Instant triggeredOn) { this.triggeredOn = triggeredOn; }
    public BigDecimal getTriggeredPrice() { return triggeredPrice; }
    public void setTriggeredPrice(BigDecimal triggeredPrice) { this.triggeredPrice = triggeredPrice; }
    public BigDecimal getLastObservedPrice() { return lastObservedPrice; }
    public void setLastObservedPrice(BigDecimal lastObservedPrice) { this.lastObservedPrice = lastObservedPrice; }
}
