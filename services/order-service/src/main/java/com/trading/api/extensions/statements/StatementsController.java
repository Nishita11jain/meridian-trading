package com.trading.api.extensions.statements;

import com.trading.api.extensions.TokenAccount;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** Trade and cash history, as JSON for the screen and as CSV for download. The account is the token's own. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class StatementsController {

    private final StatementsService statements;

    public StatementsController(StatementsService statements) {
        this.statements = statements;
    }

    @GetMapping("/trades")
    public ResponseEntity<List<TradeLine>> trades(@PathVariable Long accountId,
                                                  @RequestParam LocalDate from,
                                                  @RequestParam LocalDate to,
                                                  Principal principal) {
        return ResponseEntity.ok(statements.trades(accountId, TokenAccount.of(principal), from, to));
    }

    @GetMapping("/cash-ledger")
    public ResponseEntity<List<CashLine>> cash(@PathVariable Long accountId,
                                               @RequestParam LocalDate from,
                                               @RequestParam LocalDate to,
                                               Principal principal) {
        return ResponseEntity.ok(statements.cash(accountId, TokenAccount.of(principal), from, to));
    }

    @GetMapping("/statement/trades.csv")
    public ResponseEntity<String> tradesCsv(@PathVariable Long accountId,
                                            @RequestParam LocalDate from,
                                            @RequestParam LocalDate to,
                                            Principal principal) {
        return download(statements.tradesCsv(accountId, TokenAccount.of(principal), from, to),
                "trades-" + from + "-to-" + to + ".csv");
    }

    @GetMapping("/statement/cash.csv")
    public ResponseEntity<String> cashCsv(@PathVariable Long accountId,
                                          @RequestParam LocalDate from,
                                          @RequestParam LocalDate to,
                                          Principal principal) {
        return download(statements.cashCsv(accountId, TokenAccount.of(principal), from, to),
                "cash-" + from + "-to-" + to + ".csv");
    }

    private static ResponseEntity<String> download(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
