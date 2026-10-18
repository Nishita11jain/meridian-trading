package com.trading.api.extensions.strategies;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of trading_strategy. */
public class StrategyRow {

    private Long id;
    private Long accountId;
    private String symbol;
    private String side;
    private String strategyType;
    private Integer fastWindow;
    private Integer slowWindow;
    private Integer bandPeriod;
    private BigDecimal bandMultiplier;
    private Integer orderQuantity;
    private BigDecimal maxSpend;
    private Integer maxPositionSize;
    private Integer maxConsecutiveFailures;
    private Integer consecutiveFailures;
    private String status;
    private String disabledReason;
    private Instant createdOn;
    private Instant updatedOn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public Integer getFastWindow() { return fastWindow; }
    public void setFastWindow(Integer fastWindow) { this.fastWindow = fastWindow; }
    public Integer getSlowWindow() { return slowWindow; }
    public void setSlowWindow(Integer slowWindow) { this.slowWindow = slowWindow; }
    public Integer getBandPeriod() { return bandPeriod; }
    public void setBandPeriod(Integer bandPeriod) { this.bandPeriod = bandPeriod; }
    public BigDecimal getBandMultiplier() { return bandMultiplier; }
    public void setBandMultiplier(BigDecimal bandMultiplier) { this.bandMultiplier = bandMultiplier; }
    public Integer getOrderQuantity() { return orderQuantity; }
    public void setOrderQuantity(Integer orderQuantity) { this.orderQuantity = orderQuantity; }
    public BigDecimal getMaxSpend() { return maxSpend; }
    public void setMaxSpend(BigDecimal maxSpend) { this.maxSpend = maxSpend; }
    public Integer getMaxPositionSize() { return maxPositionSize; }
    public void setMaxPositionSize(Integer maxPositionSize) { this.maxPositionSize = maxPositionSize; }
    public Integer getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public void setMaxConsecutiveFailures(Integer maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }
    public Instant getUpdatedOn() { return updatedOn; }
    public void setUpdatedOn(Instant updatedOn) { this.updatedOn = updatedOn; }
}
