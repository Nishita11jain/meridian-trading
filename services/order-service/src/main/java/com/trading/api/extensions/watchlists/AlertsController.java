package com.trading.api.extensions.watchlists;

import com.trading.api.extensions.TokenAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/** Price alerts owned by the account the token names. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertsController {

    private final WatchlistsService watchlistsService;

    public AlertsController(WatchlistsService watchlistsService) {
        this.watchlistsService = watchlistsService;
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> list(Principal principal) {
        return ResponseEntity.ok(watchlistsService.listAlerts(TokenAccount.of(principal)));
    }

    @PostMapping
    public ResponseEntity<AlertResponse> create(@Valid @RequestBody CreateAlertRequest request, Principal principal) {
        AlertResponse created = watchlistsService.createAlert(TokenAccount.of(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AlertResponse> cancel(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(watchlistsService.cancelAlert(TokenAccount.of(principal), id));
    }
}
