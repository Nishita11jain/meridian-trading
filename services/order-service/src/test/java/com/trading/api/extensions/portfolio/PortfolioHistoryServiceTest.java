package com.trading.api.extensions.portfolio;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The history walk over stand-in data. "Now" is 2026-10-02T12:00Z; the 1D range starts 24 hours earlier
 * with a point every 5 minutes.
 */
class PortfolioHistoryServiceTest {

    private static final long ACCOUNT = 4L;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private PortfolioMapper mapper;
    private PortfolioHistoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PortfolioMapper.class);
        AccountMapper accounts = mock(AccountMapper.class);
        when(accounts.findById(ACCOUNT)).thenReturn(new AccountRow());
        when(mapper.findLastPricesBefore(anyList(), any())).thenReturn(List.of());
        when(mapper.findPriceTicks(anyList(), any(), any())).thenReturn(List.of());
        service = new PortfolioHistoryService(mapper, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HistoryFillRow fill(String symbol, String side, long qty, String price, String at, String currency) {
        HistoryFillRow row = new HistoryFillRow();
        row.setSymbol(symbol);
        row.setSide(side);
        row.setQuantity(qty);
        row.setExecutedPrice(new BigDecimal(price));
        row.setExecutedOn(Instant.parse(at));
        row.setCurrency(currency);
        return row;
    }

    private static PriceTickRow tick(String symbol, String price, String at) {
        PriceTickRow row = new PriceTickRow();
        row.setSymbol(symbol);
        row.setPrice(new BigDecimal(price));
        row.setRecordedOn(Instant.parse(at));
        return row;
    }

    private static PortfolioHistory.Point pointAt(PortfolioHistory history, String at) {
        return history.points().stream().filter(p -> p.at().equals(Instant.parse(at))).findFirst().orElseThrow();
    }

    @Test
    void valuesTheHoldingsAtTheLatestStoredPriceAndTracksWhatWasInvested() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "2000.00", "2026-10-02T09:00:00Z", "INR")));
        when(mapper.findPriceTicks(anyList(), any(), any())).thenReturn(List.of(
                tick("TCS.NS", "2050.00", "2026-10-02T09:30:00Z"),
                tick("TCS.NS", "1950.00", "2026-10-02T11:00:00Z")));

        PortfolioHistory inr = service.history(ACCOUNT, ACCOUNT, "1D").get(0);

        assertEquals("INR", inr.currency());
        assertEquals("1D", inr.range());
        // Bought at 09:00 with no stored price yet: valued at the trade price, and marked estimated.
        PortfolioHistory.Point bought = pointAt(inr, "2026-10-02T09:00:00Z");
        assertEquals(0, new BigDecimal("20000.00").compareTo(bought.value()));
        assertTrue(bought.estimated());
        assertEquals(0, new BigDecimal("20000.00").compareTo(bought.invested()));
        // Once a price is stored, that is used, and the point is no longer an estimate.
        assertEquals(0, new BigDecimal("20500.00").compareTo(pointAt(inr, "2026-10-02T10:00:00Z").value()));
        assertTrue(!pointAt(inr, "2026-10-02T10:00:00Z").estimated());
        assertEquals(0, new BigDecimal("19500.00").compareTo(pointAt(inr, "2026-10-02T11:30:00Z").value()));
        assertEquals(Instant.parse("2026-10-02T09:00:00Z"), inr.valueSince());
        assertEquals(Instant.parse("2026-10-02T09:30:00Z"), inr.marketPricesSince());
        // The last point is now.
        assertEquals(NOW, inr.points().get(inr.points().size() - 1).at());
    }

    @Test
    void aLaterTradeUpdatesTheKnownPriceOverAnOlderStoredOne() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "2000.00", "2026-10-02T09:00:00Z", "INR"),
                fill("TCS.NS", "BUY", 10, "2200.00", "2026-10-02T10:00:00Z", "INR")));
        when(mapper.findPriceTicks(anyList(), any(), any())).thenReturn(List.of(tick("TCS.NS", "2100.00", "2026-10-02T09:30:00Z")));

        PortfolioHistory inr = service.history(ACCOUNT, ACCOUNT, "1D").get(0);

        // 20 units at the 10:00 trade price of 2,200, which is later than the stored 2,100.
        assertEquals(0, new BigDecimal("44000.00").compareTo(pointAt(inr, "2026-10-02T10:30:00Z").value()));
        assertTrue(pointAt(inr, "2026-10-02T10:30:00Z").estimated());
    }

    @Test
    void theRangeStartsAtTheFirstFillWhenThatIsLater() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 1, "2000.00", "2026-10-02T09:00:00Z", "INR")));

        PortfolioHistory inr = service.history(ACCOUNT, ACCOUNT, "1D").get(0);

        assertEquals(Instant.parse("2026-10-02T09:00:00Z"), inr.points().get(0).at());
    }

    @Test
    void aPriceStoredBeforeTheRangeValuesTheStartOfIt() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "2000.00", "2026-09-20T09:00:00Z", "INR")));
        when(mapper.findLastPricesBefore(anyList(), any())).thenReturn(List.of(tick("TCS.NS", "2100.00", "2026-10-01T09:00:00Z")));

        PortfolioHistory inr = service.history(ACCOUNT, ACCOUNT, "1D").get(0);

        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), inr.points().get(0).at());
        assertEquals(0, new BigDecimal("21000.00").compareTo(inr.points().get(0).value()));
    }

    @Test
    void aSellLowersHoldingsAndInvestedAtTheAverageCost() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "2000.00", "2026-10-02T09:00:00Z", "INR"),
                fill("TCS.NS", "BUY", 10, "3000.00", "2026-10-02T09:10:00Z", "INR"),
                fill("TCS.NS", "SELL", 5, "2600.00", "2026-10-02T10:00:00Z", "INR")));
        when(mapper.findPriceTicks(anyList(), any(), any())).thenReturn(List.of(tick("TCS.NS", "2500.00", "2026-10-02T09:05:00Z")));

        PortfolioHistory inr = service.history(ACCOUNT, ACCOUNT, "1D").get(0);

        // 20 held at an average of 2,500 = 50,000 invested; after selling 5, 15 x 2,500 = 37,500.
        assertEquals(0, new BigDecimal("50000.00").compareTo(pointAt(inr, "2026-10-02T09:30:00Z").invested()));
        assertEquals(0, new BigDecimal("37500.00").compareTo(pointAt(inr, "2026-10-02T10:30:00Z").invested()));
        // The sell at 2,600 is the latest known price, so the 15 left are worth 15 x 2,600.
        assertEquals(0, new BigDecimal("39000.00").compareTo(pointAt(inr, "2026-10-02T10:30:00Z").value()));
    }

    @Test
    void eachCurrencyHasItsOwnSeriesAndTheyAreNeverAdded() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "2000.00", "2026-10-02T09:00:00Z", "INR"),
                fill("MSFT", "BUY", 1, "500.00", "2026-10-02T10:00:00Z", "USD")));
        when(mapper.findPriceTicks(anyList(), any(), any())).thenReturn(List.of(
                tick("TCS.NS", "2000.00", "2026-10-02T09:00:00Z"), tick("MSFT", "510.00", "2026-10-02T10:00:00Z")));

        List<PortfolioHistory> history = service.history(ACCOUNT, ACCOUNT, "1D");

        assertEquals(List.of("INR", "USD"), history.stream().map(PortfolioHistory::currency).toList());
        assertEquals(0, new BigDecimal("510.00").compareTo(history.get(1).points().get(history.get(1).points().size() - 1).value()));
        assertEquals(0, new BigDecimal("20000.00").compareTo(history.get(0).points().get(history.get(0).points().size() - 1).value()));
    }

    @Test
    void allSpreadsAboutThreeHundredPointsOverTheWholeHistory() {
        Duration step = PortfolioHistoryService.allStep(Instant.parse("2026-07-01T00:00:00Z"), NOW);
        assertTrue(step.toHours() >= 7 && step.toHours() <= 8, step.toString());
        assertEquals(Duration.ofMinutes(5), PortfolioHistoryService.allStep(NOW.minusSeconds(600), NOW));
    }

    @Test
    void noFillsMeansNoHistory() {
        when(mapper.findFillsForHistory(ACCOUNT)).thenReturn(List.of());

        assertEquals(List.of(), service.history(ACCOUNT, ACCOUNT, "1M"));
    }

    @Test
    void anUnknownRangeIsVal422() {
        InvalidHistoryRangeException refusal = assertThrows(InvalidHistoryRangeException.class,
                () -> service.history(ACCOUNT, ACCOUNT, "5Y"));
        assertEquals("VAL-422", refusal.code());
    }

    @Test
    void anotherAccountsHistoryIsAcc403() {
        assertThrows(AccountNotActiveException.class, () -> service.history(5L, ACCOUNT, "1D"));
    }
}
