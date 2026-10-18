package com.trading.api.extensions.advice;

import com.trading.api.extensions.TokenAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Trade Advice & Signals routes. Every route addresses an account in the path and compares it to
 * the token's accountId claim; a mismatch is ACC-403, so one customer cannot trigger or read
 * another's advice. GET never calls Fauxnance -- it only ever reads the cache; the "Analyse now"
 * POST is the one action in this module that may call out, and only past the cache's cooldown.
 */
@RestController
@RequestMapping("/api/v1/advice")
public class AdviceController {

    private final AdviceService adviceService;

    public AdviceController(AdviceService adviceService) {
        this.adviceService = adviceService;
    }

    @GetMapping("/{accountId}/signals")
    public ResponseEntity<List<SignalResponse>> signals(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(adviceService.listSignals(accountId, TokenAccount.of(principal)));
    }

    @PostMapping("/{accountId}/signals/{symbol}/analyse")
    public ResponseEntity<SignalResponse> analyse(@PathVariable Long accountId, @PathVariable String symbol,
                                                   Principal principal) {
        return ResponseEntity.ok(adviceService.analyse(accountId, TokenAccount.of(principal), symbol));
    }

    /** Health check, same shape as the other extension modules'. */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("UP"));
    }

    record HealthResponse(String status) {
    }
}
