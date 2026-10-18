package com.trading.api.dto;

import com.trading.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The one place in the whole platform where the name "accountId" means the
 * string business reference (Account.getAccountReference()) rather than the
 * numeric key -- the contract is explicit about this, and it is deliberate,
 * not an inconsistency: {@code id} carries the numeric key everywhere else
 * calls "accountId".
 */
public record AccountResponse(
        Long id,
        String accountId,
        String holderName,
        BigDecimal cashBalance,
        AccountStatus status,
        int version,
        Instant lastUpdated
) {
}
