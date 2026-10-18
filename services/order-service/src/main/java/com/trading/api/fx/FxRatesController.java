package com.trading.api.fx;

import com.trading.domain.OrderSide;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The rate an order for a dollar instrument would be locked at, so the order ticket can show the rupee cost before
 * the customer confirms. Read-only and the same for every customer, so it needs a token but no account check.
 */
@RestController
@RequestMapping("/api/v1/market/fx-rates")
public class FxRatesController {

    private final OrderFxService fxService;

    public FxRatesController(OrderFxService fxService) {
        this.fxService = fxService;
    }

    @GetMapping
    public ResponseEntity<FxRatesResponse> rates() {
        BigDecimal mid = fxService.midRate("USD");
        return ResponseEntity.ok(new FxRatesResponse(
                OrderFxService.BASE_CURRENCY,
                "USD",
                mid,
                fxService.lockedRate("USD", OrderSide.BUY),
                fxService.lockedRate("USD", OrderSide.SELL),
                fxService.marginPercent(),
                fxService.rateDate()));
    }

    /** Rupees per dollar: the stored mid rate, and the rates a buy and a sell would be locked at. */
    public record FxRatesResponse(
            String baseCurrency,
            String currency,
            BigDecimal midRate,
            BigDecimal buyRate,
            BigDecimal sellRate,
            BigDecimal marginPercent,
            LocalDate rateDate
    ) {
    }
}
