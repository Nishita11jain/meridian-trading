package com.trading.api.extensions.watchlists;

import com.trading.api.extensions.TokenAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Current market prices from the read model the market-data consumer maintains. Quotes are the same
 * for every customer (public market data), so these routes do not filter by account; they still
 * require a verified token, like every route under /api/v1. Prices never come from Fauxnance via the
 * browser -- the browser only ever reads this platform cache.
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketQuoteController {

    private final MarketDataService marketDataService;
    private final MarketFreshnessService freshnessService;

    public MarketQuoteController(MarketDataService marketDataService, MarketFreshnessService freshnessService) {
        this.marketDataService = marketDataService;
        this.freshnessService = freshnessService;
    }

    /** When prices were last stored and when the next batch is due, for refreshing in step with the feed. */
    @GetMapping("/freshness")
    public ResponseEntity<MarketFreshnessResponse> freshness(Principal principal) {
        TokenAccount.of(principal);
        return ResponseEntity.ok(freshnessService.freshness());
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<InstrumentResponse>> instruments(Principal principal) {
        TokenAccount.of(principal);
        return ResponseEntity.ok(marketDataService.getTradableInstruments());
    }

    @GetMapping("/quotes")
    public ResponseEntity<List<MarketQuoteResponse>> quotes(Principal principal) {
        TokenAccount.of(principal); // require a verified token
        return ResponseEntity.ok(marketDataService.getAllQuotes());
    }

    @GetMapping("/quotes/{symbol}")
    public ResponseEntity<MarketQuoteResponse> quote(@PathVariable String symbol, Principal principal) {
        TokenAccount.of(principal);
        return ResponseEntity.ok(marketDataService.getQuote(symbol));
    }

    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<MarketQuoteResponse>> history(@PathVariable String symbol,
                                                             @RequestParam(defaultValue = "5D") String range,
                                                             Principal principal) {
        TokenAccount.of(principal);
        return ResponseEntity.ok(marketDataService.getHistory(symbol, range));
    }
}
