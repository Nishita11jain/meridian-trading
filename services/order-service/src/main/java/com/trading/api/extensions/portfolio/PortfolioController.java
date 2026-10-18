package com.trading.api.extensions.portfolio;

import com.trading.api.extensions.TokenAccount;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Portfolio and P&L routes. Each addresses an account in the path and compares it to the token's
 * accountId claim: a mismatch is ACC-403, so one customer cannot read another's portfolio. When no
 * holding can be priced the service answers 503 MKT-503; a partial outage answers 200 with the unpriced
 * holdings marked stale and the summary marked partial.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    private final PortfolioHistoryService historyService;

    public PortfolioController(PortfolioService portfolioService, PortfolioHistoryService historyService) {
        this.portfolioService = portfolioService;
        this.historyService = historyService;
    }

    /** Value and invested amount over time, one series per currency. range: 1D, 1W, 1M or ALL (the default). */
    @GetMapping("/{accountId}/history")
    public ResponseEntity<List<PortfolioHistory>> history(@PathVariable Long accountId,
                                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "ALL") String range,
                                                          Principal principal) {
        return ResponseEntity.ok(historyService.history(accountId, TokenAccount.of(principal), range));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<PortfolioSummary> summary(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(portfolioService.summary(accountId, TokenAccount.of(principal)));
    }

    @GetMapping("/{accountId}/positions")
    public ResponseEntity<List<PricedPosition>> positions(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(portfolioService.positions(accountId, TokenAccount.of(principal)));
    }

    /**
     * from and to bound the realised figure only; unrealised is always as at now. bySymbol adds the
     * per-instrument breakdown. A malformed date is VAL-422 through the shared type-mismatch handler.
     */
    @GetMapping("/{accountId}/pnl")
    public ResponseEntity<PnlResponse> pnl(@PathVariable Long accountId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "false") boolean bySymbol,
                                           Principal principal) {
        return ResponseEntity.ok(portfolioService.pnl(accountId, TokenAccount.of(principal), from, to, bySymbol));
    }
}
