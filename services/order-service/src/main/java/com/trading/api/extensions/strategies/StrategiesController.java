package com.trading.api.extensions.strategies;

import com.trading.api.extensions.TokenAccount;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Automated Strategy Execution routes. Every route addresses an account in the path and compares it
 * to the token's accountId claim; a mismatch is ACC-403. Evaluation and order placement never
 * happen on this path -- that is {@link StrategyExecutionService}, driven only by Kafka.
 */
@RestController
@RequestMapping("/api/v1/strategies")
public class StrategiesController {

    private final StrategiesService strategiesService;

    public StrategiesController(StrategiesService strategiesService) {
        this.strategiesService = strategiesService;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<List<StrategyResponse>> list(@PathVariable Long accountId, Principal principal) {
        return ResponseEntity.ok(strategiesService.list(accountId, TokenAccount.of(principal)));
    }

    @PostMapping("/{accountId}")
    public ResponseEntity<StrategyResponse> create(@PathVariable Long accountId,
                                                     @Valid @RequestBody StrategyRequest request,
                                                     Principal principal) {
        return ResponseEntity.ok(strategiesService.create(accountId, TokenAccount.of(principal), request));
    }

    @PutMapping("/{accountId}/{strategyId}/status")
    public ResponseEntity<StrategyResponse> updateStatus(@PathVariable Long accountId, @PathVariable Long strategyId,
                                                           @Valid @RequestBody UpdateStrategyStatusRequest request,
                                                           Principal principal) {
        return ResponseEntity.ok(strategiesService.updateStatus(accountId, TokenAccount.of(principal), strategyId, request));
    }

    @GetMapping("/{accountId}/{strategyId}/log")
    public ResponseEntity<List<StrategyExecutionResponse>> log(@PathVariable Long accountId, @PathVariable Long strategyId,
                                                                 @RequestParam(defaultValue = "20") int limit,
                                                                 Principal principal) {
        return ResponseEntity.ok(strategiesService.log(accountId, TokenAccount.of(principal), strategyId, limit));
    }

    /** Health check, same shape as the other extension modules'. */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("UP"));
    }

    record HealthResponse(String status) {
    }
}
