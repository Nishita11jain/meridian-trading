package com.trading.api.extensions.statements;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One cash movement: a buy or sell settling, or a deposit or withdrawal. Positive means cash came in. */
public class CashLine {

    private LocalDateTime createdAt;
    private String type;
    private BigDecimal amount;
    private String reference;

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
}
