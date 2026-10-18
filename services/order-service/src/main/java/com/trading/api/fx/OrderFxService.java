package com.trading.api.fx;

import com.trading.domain.OrderSide;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The exchange rate an order is placed at. INR is the only currency that holds money, so a dollar order is
 * converted once, at placement, and the rate is locked on the order: cash is reserved and later settled at that
 * same rate, whatever the market does in between.
 *
 * <p>The locked rate is the stored daily mid rate moved by a margin in the platform's favour: a buyer pays a
 * little more rupees per dollar and a seller receives a little less, so the platform never gives away the spread.
 * Rupee orders have no rate (null) and move cash exactly as before.
 */
@Service
public class OrderFxService {

    static final String BASE_CURRENCY = "INR";

    private final OrderFxMapper mapper;
    private final BigDecimal marginPercent;

    public OrderFxService(OrderFxMapper mapper, @Value("${fx.margin-percent:0.5}") BigDecimal marginPercent) {
        this.mapper = mapper;
        this.marginPercent = marginPercent;
    }

    /**
     * The rate to lock on an order for an instrument in {@code currency}, or null for a rupee instrument.
     *
     * @throws FxRateUnavailableException when the currency needs converting and no rate is stored
     */
    public BigDecimal lockedRate(String currency, OrderSide side) {
        if (currency == null || BASE_CURRENCY.equals(currency)) {
            return null;
        }
        BigDecimal mid = midRate(currency);
        BigDecimal factor = side == OrderSide.BUY
                ? BigDecimal.ONE.add(marginPercent.movePointLeft(2))
                : BigDecimal.ONE.subtract(marginPercent.movePointLeft(2));
        return mid.multiply(factor).setScale(6, RoundingMode.HALF_UP);
    }

    /** The stored mid rate for a currency, with no margin. */
    public BigDecimal midRate(String currency) {
        BigDecimal mid = "USD".equals(currency) ? mapper.findLatest("USDINR") : null;
        if (mid == null) {
            throw new FxRateUnavailableException("no exchange rate is available to convert " + currency + " to " + BASE_CURRENCY);
        }
        return mid;
    }

    public LocalDate rateDate() {
        return mapper.findLatestDate("USDINR");
    }

    public BigDecimal marginPercent() {
        return marginPercent;
    }
}
