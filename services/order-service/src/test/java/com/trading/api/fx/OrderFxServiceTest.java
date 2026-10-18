package com.trading.api.fx;

import com.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderFxServiceTest {

    private final OrderFxMapper mapper = mock(OrderFxMapper.class);
    private final OrderFxService service = new OrderFxService(mapper, new BigDecimal("0.5"));

    @Test
    void aBuyIsLockedAtTheMidRatePlusTheMargin() {
        when(mapper.findLatest("USDINR")).thenReturn(new BigDecimal("96.450000"));

        assertEquals(new BigDecimal("96.932250"), service.lockedRate("USD", OrderSide.BUY));  // 96.45 x 1.005
    }

    @Test
    void aSellIsLockedAtTheMidRateMinusTheMargin() {
        when(mapper.findLatest("USDINR")).thenReturn(new BigDecimal("96.450000"));

        assertEquals(new BigDecimal("95.967750"), service.lockedRate("USD", OrderSide.SELL));  // 96.45 x 0.995
    }

    @Test
    void aRupeeInstrumentNeedsNoRate() {
        assertNull(service.lockedRate("INR", OrderSide.BUY));
    }

    @Test
    void noStoredRateMeansADollarOrderCannotBePlaced() {
        when(mapper.findLatest("USDINR")).thenReturn(null);

        FxRateUnavailableException refusal = assertThrows(FxRateUnavailableException.class,
                () -> service.lockedRate("USD", OrderSide.BUY));

        assertEquals("MKT-503", refusal.code());
    }

    @Test
    void aCurrencyWeCannotConvertIsRefusedToo() {
        assertThrows(FxRateUnavailableException.class, () -> service.lockedRate("GBP", OrderSide.BUY));
    }
}
