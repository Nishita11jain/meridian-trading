package com.trading.api.extensions.portfolio;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.domain.Money;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The portfolio arithmetic, which the brief calls most of the assessment: cost basis, market value,
 * unrealised P&L from the current price, realised P&L booked from past sells (never today's price),
 * per-currency totals, the partial and all-unpriced paths, and the access check.
 */
class PortfolioServiceTest {

    private static final long ACCOUNT = 4L;

    private PortfolioMapper portfolioMapper;
    private AccountMapper accountMapper;
    private InstrumentMapper instrumentMapper;
    private FxRateMapper fxRateMapper;
    private PortfolioService service;

    @BeforeEach
    void setUp() {
        portfolioMapper = mock(PortfolioMapper.class);
        accountMapper = mock(AccountMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        AccountRow account = new AccountRow();
        account.setId(ACCOUNT);
        account.setCashBalance(Money.of("1000.00"));
        when(accountMapper.findById(ACCOUNT)).thenReturn(account);
        when(portfolioMapper.findFilledOrders(ACCOUNT)).thenReturn(List.of());
        fxRateMapper = mock(FxRateMapper.class);
        when(fxRateMapper.findLatest("USDINR")).thenReturn(new BigDecimal("80"));
        service = new PortfolioService(portfolioMapper, accountMapper, instrumentMapper, new BaseCurrencyConverter(fxRateMapper));
    }

    private static PricedPositionRow position(String symbol, int qty, String avg, String currency, String lastPrice, boolean stale) {
        PricedPositionRow row = new PricedPositionRow();
        row.setSymbol(symbol);
        row.setQuantity(qty);
        row.setAverageCost(new BigDecimal(avg));
        row.setCurrency(currency);
        row.setLastPrice(lastPrice == null ? null : new BigDecimal(lastPrice));
        row.setQuoteAsOf(lastPrice == null ? null : Instant.parse("2026-10-01T09:00:00Z"));
        row.setStale(stale);
        return row;
    }

    private static FilledOrderRow fill(String symbol, String side, int qty, String price, String day) {
        FilledOrderRow row = fill(symbol, side, qty, price);
        row.setExecutedOn(Instant.parse(day + "T10:00:00Z"));
        return row;
    }

    private static FilledOrderRow fill(String symbol, String side, int qty, String price) {
        FilledOrderRow row = new FilledOrderRow();
        row.setSymbol(symbol);
        row.setSide(side);
        row.setQuantity(qty);
        row.setExecutedPrice(new BigDecimal(price));
        return row;
    }

    @Test
    void pricesAHoldingWithCostBasisMarketValueAndUnrealised() {
        when(portfolioMapper.findPricedPositions(ACCOUNT))
                .thenReturn(List.of(position("TCS.NS", 10, "3400", "INR", "3500", false)));

        PricedPosition p = service.positions(ACCOUNT, ACCOUNT).get(0);

        assertEquals(0, new BigDecimal("34000.00").compareTo(p.costBasis()));   // 10 * 3400
        assertEquals(0, new BigDecimal("35000.00").compareTo(p.marketValue())); // 10 * 3500
        assertEquals(0, new BigDecimal("1000.00").compareTo(p.unrealisedPnl())); // mv - cb
        assertTrue(p.unrealisedPnlPercent().compareTo(new BigDecimal("2.94")) == 0);
        assertEquals("INR", p.currency());
        assertTrue(!p.stale());
    }

    @Test
    void anUnpricedHoldingHasNoMarketValueAndIsStale() {
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of(
                position("TCS.NS", 10, "3400", "INR", "3500", false),
                position("MSFT", 2, "400", "USD", null, false)));

        PricedPosition msft = service.positions(ACCOUNT, ACCOUNT).stream().filter(p -> p.symbol().equals("MSFT")).findFirst().orElseThrow();

        assertNull(msft.marketValue());
        assertNull(msft.unrealisedPnl());
        assertTrue(msft.stale(), "no price means stale");
        assertEquals(0, new BigDecimal("800.00").compareTo(msft.costBasis()), "cost basis is still known");
    }

    @Test
    void allHoldingsUnpricedIsA503() {
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of(
                position("TCS.NS", 10, "3400", "INR", null, false)));

        PricingUnavailableException outage = assertThrows(PricingUnavailableException.class,
                () -> service.positions(ACCOUNT, ACCOUNT));
        assertEquals("MKT-503", outage.code());
    }

    @Test
    void anEmptyPortfolioIsNotAnOutage() {
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of());

        assertTrue(service.positions(ACCOUNT, ACCOUNT).isEmpty());
    }

    @Test
    void theSummaryIsInRupeesAndAnUnpricedHoldingMakesItPartial() {
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of(
                position("TCS.NS", 10, "3400", "INR", "3500", false),
                position("MSFT", 2, "400", "USD", null, false))); // unpriced -> partial

        PortfolioSummary summary = service.summary(ACCOUNT, ACCOUNT);

        assertEquals("INR", summary.baseCurrency());
        assertTrue(summary.partial(), "an unpriced holding makes the summary partial");
        assertEquals(2, summary.positionCount());
        assertEquals(0, new BigDecimal("1000.00").compareTo(summary.cashBalance()));
        assertEquals(0, new BigDecimal("35000.00").compareTo(summary.marketValue()));
        assertEquals(0, new BigDecimal("98000.00").compareTo(summary.costBasis()));   // 34,000 + 800 USD * 80
        assertEquals(0, new BigDecimal("1000.00").compareTo(summary.unrealisedPnl()));
        assertEquals(0, new BigDecimal("36000.00").compareTo(summary.totalValue()));  // cash + market value
    }

    @Test
    void aDollarHoldingIsConvertedAtTheStoredRate() {
        when(portfolioMapper.findPricedPositions(ACCOUNT))
                .thenReturn(List.of(position("MSFT", 2, "400", "USD", "410", false)));

        PortfolioSummary summary = service.summary(ACCOUNT, ACCOUNT);

        assertEquals(0, new BigDecimal("65600.00").compareTo(summary.marketValue()));  // 2 * 410 * 80
        assertEquals(0, new BigDecimal("64000.00").compareTo(summary.costBasis()));    // 2 * 400 * 80
        assertEquals(0, new BigDecimal("1600.00").compareTo(summary.unrealisedPnl()));
        assertEquals(0, new BigDecimal("2.50").compareTo(summary.unrealisedPnlPercent()));
        assertFalse(summary.partial());
    }

    @Test
    void aDollarHoldingWithNoRateIsLeftOutAndMarksTheSummaryPartial() {
        when(fxRateMapper.findLatest("USDINR")).thenReturn(null);
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of(
                position("TCS.NS", 10, "3400", "INR", "3500", false),
                position("MSFT", 2, "400", "USD", "410", false)));

        PortfolioSummary summary = service.summary(ACCOUNT, ACCOUNT);

        assertTrue(summary.partial());
        assertEquals(0, new BigDecimal("35000.00").compareTo(summary.marketValue()), "only the rupee holding is counted");
    }

    @Test
    void realisedPnlIsBookedFromSellsAgainstTheAverageCostAtTheTime() {
        // BUY 10 @100, BUY 10 @200 -> avg 150; SELL 5 @250 -> realised (250-150)*5 = 500.
        when(portfolioMapper.findFilledOrders(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "100"),
                fill("TCS.NS", "BUY", 10, "200"),
                fill("TCS.NS", "SELL", 5, "250")));
        when(portfolioMapper.findPricedPositions(ACCOUNT))
                .thenReturn(List.of(position("TCS.NS", 15, "150", "INR", "150", false)));

        PnlResponse pnl = service.pnl(ACCOUNT, ACCOUNT, null, null, true);
        SymbolPnl tcs = pnl.bySymbol().stream().filter(p -> p.symbol().equals("TCS.NS")).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("500.00").compareTo(tcs.realisedPnl()));
        assertEquals(0, new BigDecimal("500.00").compareTo(pnl.realisedPnl()));
        assertEquals(0, new BigDecimal("500.00").compareTo(pnl.totalPnl()), "unrealised is zero at the average cost");
        assertEquals("INR", pnl.baseCurrency());
    }

    @Test
    void realisedPnlForAFullySoldDollarSymbolIsConvertedAndHasNoUnrealised() {
        when(portfolioMapper.findFilledOrders(ACCOUNT)).thenReturn(List.of(
                fill("MSFT", "BUY", 1, "400"),
                fill("MSFT", "SELL", 1, "500")));
        when(portfolioMapper.findPricedPositions(ACCOUNT))
                .thenReturn(List.of(position("TCS.NS", 1, "100", "INR", "110", false)));
        when(instrumentMapper.findBySymbol("MSFT"))
                .thenReturn(new com.trading.domain.Instrument("MSFT", "Microsoft", "EQUITY", "USD", true));

        SymbolPnl msft = service.pnl(ACCOUNT, ACCOUNT, null, null, true).bySymbol().stream()
                .filter(p -> p.symbol().equals("MSFT")).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("8000.00").compareTo(msft.realisedPnl()));   // 100 USD * 80
        assertEquals(0, BigDecimal.ZERO.compareTo(msft.unrealisedPnl()), "a sold-out symbol has no current holding to value");
    }

    @Test
    void theBreakdownIsLeftOutUnlessAskedFor() {
        when(portfolioMapper.findPricedPositions(ACCOUNT)).thenReturn(List.of());

        assertNull(service.pnl(ACCOUNT, ACCOUNT, null, null, false).bySymbol());
    }

    @Test
    void fromAndToBoundTheRealisedFigureByTheDayOfTheSale() {
        when(portfolioMapper.findFilledOrders(ACCOUNT)).thenReturn(List.of(
                fill("TCS.NS", "BUY", 10, "100", "2026-09-01"),
                fill("TCS.NS", "SELL", 2, "150", "2026-09-10"),   // +100, before the range
                fill("TCS.NS", "SELL", 2, "170", "2026-10-02")));  // +140, inside it
        when(portfolioMapper.findPricedPositions(ACCOUNT))
                .thenReturn(List.of(position("TCS.NS", 6, "100", "INR", "100", false)));

        PnlResponse inRange = service.pnl(ACCOUNT, ACCOUNT, java.time.LocalDate.parse("2026-10-01"),
                java.time.LocalDate.parse("2026-10-31"), false);
        PnlResponse everything = service.pnl(ACCOUNT, ACCOUNT, null, null, false);

        assertEquals(0, new BigDecimal("140.00").compareTo(inRange.realisedPnl()));
        assertEquals(0, new BigDecimal("240.00").compareTo(everything.realisedPnl()));
        assertEquals(java.time.LocalDate.parse("2026-10-01"), inRange.from());
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() {
        InvalidHistoryRangeException refusal = assertThrows(InvalidHistoryRangeException.class,
                () -> service.pnl(ACCOUNT, ACCOUNT, java.time.LocalDate.parse("2026-10-31"),
                        java.time.LocalDate.parse("2026-10-01"), false));

        assertEquals("VAL-422", refusal.code());
    }

    @Test
    void anotherAccountsPortfolioIsRefusedWithAcc403() {
        AccountNotActiveException refusal = assertThrows(AccountNotActiveException.class,
                () -> service.summary(5L, ACCOUNT));

        assertEquals("ACC-403", refusal.code());
    }
}
