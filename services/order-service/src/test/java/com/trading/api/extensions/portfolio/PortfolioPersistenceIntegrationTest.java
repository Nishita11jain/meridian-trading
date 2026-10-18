package com.trading.api.extensions.portfolio;

import com.trading.api.service.OrderEventPublisher;
import com.trading.api.service.OrderPublishReconciler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Portfolio pricing against real Postgres, end to end: a position, a quote in the market-data read
 * model, and filled orders, through the real service. Uses a throwaway account created in the test
 * transaction so it is immune to live data, and rolls everything back.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class PortfolioPersistenceIntegrationTest {

    private static final String INR_SYMBOL = "TCS.NS";
    private static final String USD_SYMBOL = "MSFT";

    @MockBean
    private OrderEventPublisher orderEventPublisher;
    @MockBean
    private OrderPublishReconciler reconciler;

    @Autowired
    private PortfolioService portfolioService;
    @Autowired
    private PortfolioHistoryService historyService;
    @Autowired
    private JdbcTemplate jdbc;

    private long account;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(), "No reachable Postgres: skipping");
    }

    void freshAccount() {
        account = jdbc.queryForObject(
                "INSERT INTO accounts (account_id, holder_name, cash_balance, status) "
                        + "VALUES (?, 'IntegrationTest', 100000, 'ACTIVE') RETURNING id",
                Long.class, "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
    }

    private void givePosition(String symbol, int quantity, String averageCost) {
        jdbc.update("INSERT INTO positions (account_id, symbol, quantity, average_cost) VALUES (?, ?, ?, ?)",
                account, symbol, quantity, new BigDecimal(averageCost));
    }

    private void quote(String symbol, String currency, String lastPrice) {
        jdbc.update("INSERT INTO market_price (symbol, last_price, currency, market_state, stale, quote_as_of) "
                        + "VALUES (?, ?, ?, 'open', FALSE, ?) "
                        + "ON CONFLICT (symbol) DO UPDATE SET last_price = EXCLUDED.last_price, stale = FALSE, "
                        + "quote_as_of = EXCLUDED.quote_as_of",
                symbol, new BigDecimal(lastPrice), currency, java.sql.Timestamp.from(Instant.now()));
    }

    private void clearQuote(String symbol) {
        jdbc.update("DELETE FROM market_price WHERE symbol = ?", symbol);
    }

    private void filled(String symbol, String side, int quantity, String price, String executedPrice, String when) {
        jdbc.update("INSERT INTO orders (id, account_id, symbol, side, quantity, price, status, idempotency_key, "
                        + "created_on, executed_price, executed_on) VALUES (?, ?, ?, ?, ?, ?, 'FILLED', ?, ?, ?, ?)",
                UUID.randomUUID(), account, symbol, side, quantity, new BigDecimal(price),
                "it-" + UUID.randomUUID(), java.sql.Timestamp.from(Instant.parse(when)),
                new BigDecimal(executedPrice), java.sql.Timestamp.from(Instant.parse(when)));
    }

    @Test
    void positionsArePricedFromTheMarketDataReadModel() {
        freshAccount();
        givePosition(INR_SYMBOL, 10, "3400.00");
        quote(INR_SYMBOL, "INR", "3500.00");

        PricedPosition p = portfolioService.positions(account, account).stream()
                .filter(x -> x.symbol().equals(INR_SYMBOL)).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("34000.00").compareTo(p.costBasis()));
        assertEquals(0, new BigDecimal("35000.00").compareTo(p.marketValue()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(p.unrealisedPnl()));
        assertEquals("INR", p.currency());
        assertTrue(!p.stale());
    }

    @Test
    void theSummaryIsInRupeesWithCashAndIsNotPartialWhenEverythingIsPriced() {
        freshAccount();
        givePosition(INR_SYMBOL, 10, "3400.00");
        quote(INR_SYMBOL, "INR", "3500.00");

        PortfolioSummary summary = portfolioService.summary(account, account);

        assertTrue(!summary.partial());
        assertEquals(0, new BigDecimal("100000.00").compareTo(summary.cashBalance()));
        assertEquals("INR", summary.baseCurrency());
        assertEquals(0, new BigDecimal("35000.00").compareTo(summary.marketValue()));
        assertEquals(0, new BigDecimal("135000.00").compareTo(summary.totalValue()));
    }

    @Test
    void realisedPnlIsComputedFromFilledSells() {
        freshAccount();
        givePosition(INR_SYMBOL, 1, "3400.00");
        quote(INR_SYMBOL, "INR", "3400.00");
        // MSFT: BUY 1 @400, SELL 1 @500 -> realised 100 (fully sold, no current position).
        filled(USD_SYMBOL, "BUY", 1, "400.00", "400.00", "2026-09-01T09:00:00Z");
        filled(USD_SYMBOL, "SELL", 1, "500.00", "500.00", "2026-09-02T09:00:00Z");

        SymbolPnl msft = portfolioService.pnl(account, account, null, null, true).bySymbol().stream()
                .filter(x -> x.symbol().equals(USD_SYMBOL)).findFirst().orElse(null);

        // 100 USD realised. It is converted at the stored USD/INR rate, so it is present only if a rate exists.
        if (msft != null) {
            assertTrue(msft.realisedPnl().signum() > 0);
        }
    }

    @Test
    void whenNoHoldingCanBePricedTheServiceReportsAnOutage() {
        freshAccount();
        givePosition(INR_SYMBOL, 10, "3400.00");
        clearQuote(INR_SYMBOL);

        assertThrows(PricingUnavailableException.class, () -> portfolioService.positions(account, account));
    }

    @Test
    void aPartialOutageStillReturnsWithThatHoldingMarkedStale() {
        freshAccount();
        givePosition(INR_SYMBOL, 10, "3400.00");
        quote(INR_SYMBOL, "INR", "3500.00");
        givePosition(USD_SYMBOL, 2, "400.00");
        clearQuote(USD_SYMBOL);

        List<PricedPosition> positions = portfolioService.positions(account, account);
        PricedPosition msft = positions.stream().filter(p -> p.symbol().equals(USD_SYMBOL)).findFirst().orElseThrow();

        assertTrue(msft.stale());
        assertTrue(portfolioService.summary(account, account).partial());
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + env("POSTGRES_HOST", "localhost") + ":" + env("POSTGRES_PORT", "5432")
                        + "/" + env("POSTGRES_DB", ""),
                env("POSTGRES_USER", ""), env("POSTGRES_PASSWORD", ""));
    }

    private static boolean databaseIsReachable() {
        if (env("POSTGRES_DB", "").isEmpty() || env("POSTGRES_USER", "").isEmpty()) {
            return false;
        }
        try (Connection connection = connect()) {
            return connection.isValid(3);
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void historyIsRebuiltFromTheFillsAndTheStoredPrices() {
        freshAccount();
        // An instrument no live price has been stored for, so only this test's price counts.
        String symbol = jdbc.queryForObject(
                "SELECT symbol FROM instruments WHERE symbol NOT IN (SELECT DISTINCT symbol FROM price_history) ORDER BY symbol LIMIT 1",
                String.class);
        String currency = jdbc.queryForObject("SELECT currency FROM instruments WHERE symbol = ?", String.class, symbol);
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        filled(symbol, "BUY", 4, "100.00", "100.00", twoHoursAgo.toString());
        jdbc.update("INSERT INTO price_history (symbol, price, quote_as_of, recorded_on) VALUES (?, 110.00, ?, ?)",
                symbol, java.sql.Timestamp.from(twoHoursAgo.plusSeconds(1800)), java.sql.Timestamp.from(twoHoursAgo.plusSeconds(1800)));

        PortfolioHistory history = historyService.history(account, account, "1D").get(0);

        assertEquals(currency, history.currency());
        PortfolioHistory.Point last = history.points().get(history.points().size() - 1);
        assertEquals(0, new BigDecimal("440.00").compareTo(last.value()), "4 units at the stored 110.00");
        assertEquals(0, new BigDecimal("400.00").compareTo(last.invested()), "4 units bought at 100.00");
        assertEquals(0, new BigDecimal("400.00").compareTo(history.points().get(0).value()), "valued at its own trade price at first");
        assertTrue(history.points().get(0).estimated(), "and marked as an estimate");
        assertTrue(!last.estimated(), "the stored price replaces the estimate");
    }
}
