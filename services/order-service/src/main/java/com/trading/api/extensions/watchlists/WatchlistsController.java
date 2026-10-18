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

/** Watchlists owned by the account the token names. The account is never taken from the request. */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistsController {

    private final WatchlistsService watchlistsService;

    public WatchlistsController(WatchlistsService watchlistsService) {
        this.watchlistsService = watchlistsService;
    }

    @GetMapping
    public ResponseEntity<List<WatchlistResponse>> list(Principal principal) {
        return ResponseEntity.ok(watchlistsService.listWatchlists(TokenAccount.of(principal)));
    }

    @PostMapping
    public ResponseEntity<WatchlistResponse> create(@Valid @RequestBody CreateWatchlistRequest request,
                                                    Principal principal) {
        WatchlistResponse created = watchlistsService.createWatchlist(TokenAccount.of(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/presets")
    public ResponseEntity<List<WatchlistPresetResponse>> presets(Principal principal) {
        TokenAccount.of(principal);
        return ResponseEntity.ok(watchlistsService.presets());
    }

    @PostMapping("/presets/{key}")
    public ResponseEntity<WatchlistResponse> createFromPreset(@PathVariable String key, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlistsService.createFromPreset(TokenAccount.of(principal), key));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/instruments/{symbol}/flag")
    public ResponseEntity<WatchlistResponse> setItemFlag(@PathVariable Long id, @PathVariable String symbol,
                                                         @Valid @RequestBody SetItemFlagRequest request, Principal principal) {
        return ResponseEntity.ok(watchlistsService.setItemFlag(TokenAccount.of(principal), id, symbol, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WatchlistResponse> get(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(watchlistsService.getWatchlist(TokenAccount.of(principal), id));
    }

    @PostMapping("/{id}/instruments")
    public ResponseEntity<WatchlistResponse> addInstrument(@PathVariable Long id,
                                                           @Valid @RequestBody AddInstrumentRequest request,
                                                           Principal principal) {
        return ResponseEntity.ok(watchlistsService.addInstrument(TokenAccount.of(principal), id, request));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    public ResponseEntity<WatchlistResponse> rename(@PathVariable Long id, @Valid @RequestBody RenameWatchlistRequest request,
                                                    Principal principal) {
        return ResponseEntity.ok(watchlistsService.renameWatchlist(TokenAccount.of(principal), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        watchlistsService.deleteWatchlist(TokenAccount.of(principal), id);
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/instruments/order")
    public ResponseEntity<WatchlistResponse> reorder(@PathVariable Long id, @Valid @RequestBody ReorderWatchlistRequest request,
                                                     Principal principal) {
        return ResponseEntity.ok(watchlistsService.reorderInstruments(TokenAccount.of(principal), id, request));
    }

    @DeleteMapping("/{id}/instruments/{symbol}")
    public ResponseEntity<WatchlistResponse> removeInstrument(@PathVariable Long id, @PathVariable String symbol,
                                                              Principal principal) {
        return ResponseEntity.ok(watchlistsService.removeInstrument(TokenAccount.of(principal), id, symbol));
    }
}
