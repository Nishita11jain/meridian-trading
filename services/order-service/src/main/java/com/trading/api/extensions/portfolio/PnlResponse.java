package com.trading.api.extensions.portfolio;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Profit and loss in the base currency. {@code from} and {@code to} bound the realised figure only; unrealised is
 * always as at {@code asOf}. {@code bySymbol} is present only when it was asked for.
 */
public record PnlResponse(
        Long accountId,
        String baseCurrency,
        LocalDate from,
        LocalDate to,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        BigDecimal totalPnl,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SymbolPnl> bySymbol,
        Instant asOf
) {
}
