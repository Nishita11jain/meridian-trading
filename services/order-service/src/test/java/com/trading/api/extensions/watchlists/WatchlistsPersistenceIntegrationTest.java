package com.trading.api.extensions.watchlists;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The watchlists module against real Postgres: the market-data read model, watchlist CRUD, and the
 * whole alert chain -- a quote crosses a threshold, the alert flips to TRIGGERED, and a notification is
 * recorded through the real notifications module. Self-skips without a database; rolled back each test.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class WatchlistsPersistenceIntegrationTest {

    @MockBean
    private OrderEventPublisher orderEventPublisher;
    @MockBean
    private OrderPublishReconciler reconciler;

    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private WatchlistsService watchlistsService;
    @Autowired
    private JdbcTemplate jdbc;

    private long account;
    private String symbol;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(), "No reachable Postgres: skipping");
    }

    void resolveSeed() {
        // A throwaway account created inside the test transaction (rolled back with it), so the alert and
        // watchlist assertions for this account are unaffected by anything live usage has written.
        account = jdbc.queryForObject(
                "INSERT INTO accounts (account_id, holder_name, cash_balance, status) "
                        + "VALUES (?, 'IntegrationTest', 100000, 'ACTIVE') RETURNING id",
                Long.class, "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        symbol = jdbc.queryForObject("SELECT symbol FROM instruments WHERE tradable = TRUE ORDER BY symbol LIMIT 1", String.class);
    }

    private MarketDataService.MarketQuote quote(String price) {
        return new MarketDataService.MarketQuote(symbol, new BigDecimal(price), null, null, "INR",
                null, null, null, "open", false, Instant.parse("2026-10-01T09:00:00Z"));
    }

    @Test
    void aQuoteIsStoredAndReadableAndHistoryGrows() {
        resolveSeed();

        marketDataService.ingestQuote(UUID.randomUUID(), quote("100.00"));

        MarketQuoteResponse stored = marketDataService.getQuote(symbol);
        assertEquals(0, new BigDecimal("100.00").compareTo(stored.lastPrice()));
        assertFalse(marketDataService.getHistory(symbol).isEmpty(), "the tick is appended to history");
    }

    @Test
    void aWatchlistCanBeCreatedAddedToListedAndTrimmed() {
        resolveSeed();

        WatchlistResponse created = watchlistsService.createWatchlist(account, new CreateWatchlistRequest("Tech " + UUID.randomUUID()));
        watchlistsService.addInstrument(account, created.id(), new AddInstrumentRequest(symbol));

        WatchlistResponse withItem = watchlistsService.getWatchlist(account, created.id());
        assertEquals(1, withItem.items().size());
        assertEquals(symbol, withItem.items().get(0).symbol());

        WatchlistResponse trimmed = watchlistsService.removeInstrument(account, created.id(), symbol);
        assertTrue(trimmed.items().isEmpty());
    }

    @Test
    void anAboveAlertFiresOnceWhenCrossedAndRecordsANotification() {
        resolveSeed();
        AlertResponse alert = watchlistsService.createAlert(account,
                new CreateAlertRequest(symbol, "ABOVE", new BigDecimal("200.00")));

        marketDataService.ingestQuote(UUID.randomUUID(), quote("150.00")); // below: no fire, observes
        UUID crossing = UUID.randomUUID();
        marketDataService.ingestQuote(crossing, quote("250.00"));          // crosses up: fires

        String status = jdbc.queryForObject("SELECT status FROM price_alerts WHERE id = ?", String.class, alert.id());
        assertEquals("TRIGGERED", status);
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE account_id = ? AND type = 'PRICE_ALERT_TRIGGERED'",
                Integer.class, account);
        assertEquals(1, notifications, "the crossing delivered exactly one notification");

        // A redelivery of the crossing quote is a no-op: no second notification, no change.
        assertFalse(marketDataService.ingestQuote(crossing, quote("250.00")));
        Integer afterReplay = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE account_id = ? AND type = 'PRICE_ALERT_TRIGGERED'",
                Integer.class, account);
        assertEquals(1, afterReplay);
    }

    @Test
    void anAlertThatIsNotCrossedDoesNotFire() {
        resolveSeed();
        AlertResponse alert = watchlistsService.createAlert(account,
                new CreateAlertRequest(symbol, "ABOVE", new BigDecimal("1000000.00")));

        marketDataService.ingestQuote(UUID.randomUUID(), quote("150.00"));

        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM price_alerts WHERE id = ?", String.class, alert.id()));
    }

    @Test
    void aCancelledAlertDoesNotFire() {
        resolveSeed();
        AlertResponse alert = watchlistsService.createAlert(account,
                new CreateAlertRequest(symbol, "ABOVE", new BigDecimal("200.00")));
        watchlistsService.cancelAlert(account, alert.id());

        marketDataService.ingestQuote(UUID.randomUUID(), quote("250.00"));

        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM price_alerts WHERE id = ?", String.class, alert.id()));
    }

    private MarketDataService.MarketQuote quoteAt(String price, String at) {
        return new MarketDataService.MarketQuote(symbol, new BigDecimal(price), null, null, "INR",
                null, null, null, "closed", false, Instant.parse(at));
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM price_history WHERE symbol = ?", Integer.class, symbol);
    }

    @Test
    void aRepeatedQuoteIsNotStoredAgainButAChangeIs() {
        resolveSeed();
        // Quote times far ahead of any live data, so these are the symbol's latest ticks.
        int before = historyRows();

        marketDataService.ingestQuote(UUID.randomUUID(), quoteAt("100.00", "2099-01-01T09:00:00Z"));
        marketDataService.ingestQuote(UUID.randomUUID(), quoteAt("100.00", "2099-01-01T09:00:00Z")); // closed: same close again
        assertEquals(before + 1, historyRows(), "the repeated close is not stored twice");

        marketDataService.ingestQuote(UUID.randomUUID(), quoteAt("101.00", "2099-01-01T09:00:00Z"));
        marketDataService.ingestQuote(UUID.randomUUID(), quoteAt("101.00", "2099-01-01T09:05:00Z"));
        assertEquals(before + 3, historyRows(), "a new price, or a new quote time, is stored");
    }

    @Test
    void theTrendReadsEachPriceUpdateOnceEvenWhenOldDataHasCopies() {
        resolveSeed();
        // Rows as the poller wrote them before repeats were skipped: one quote, stored three times.
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO price_history (symbol, price, quote_as_of) VALUES (?, 200.00, '2099-02-01 15:15:00')", symbol);
        }
        jdbc.update("INSERT INTO price_history (symbol, price, quote_as_of) VALUES (?, 190.00, '2099-02-01 15:00:00')", symbol);

        var history = marketDataService.getHistory(symbol);

        assertEquals(0, new BigDecimal("200.00").compareTo(history.get(0).lastPrice()), "newest first");
        assertEquals(0, new BigDecimal("190.00").compareTo(history.get(1).lastPrice()), "the copies count once");
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

    @Autowired
    private MarketFreshnessService freshnessService;

    @Test
    void instrumentsKeepTheOrderTheyWereAddedInAndCanBeReordered() {
        resolveSeed();
        List<String> tradable = jdbc.queryForList("SELECT symbol FROM instruments WHERE tradable = TRUE ORDER BY symbol LIMIT 3", String.class);
        WatchlistResponse list = watchlistsService.createWatchlist(account, new CreateWatchlistRequest("Order " + UUID.randomUUID()));
        // Added in reverse alphabetical order: they stay in that order, not sorted by symbol.
        for (int i = tradable.size() - 1; i >= 0; i--) {
            watchlistsService.addInstrument(account, list.id(), new AddInstrumentRequest(tradable.get(i)));
        }
        List<String> added = watchlistsService.getWatchlist(account, list.id()).items().stream().map(WatchlistItemResponse::symbol).toList();
        assertEquals(List.of(tradable.get(2), tradable.get(1), tradable.get(0)), added);

        WatchlistResponse reordered = watchlistsService.reorderInstruments(account, list.id(),
                new ReorderWatchlistRequest(List.of(tradable.get(0))));

        assertEquals(List.of(tradable.get(0), tradable.get(2), tradable.get(1)),
                reordered.items().stream().map(WatchlistItemResponse::symbol).toList());
    }

    @Test
    void aWatchlistCanBeRenamedAndDeletedWithItsItems() {
        resolveSeed();
        WatchlistResponse list = watchlistsService.createWatchlist(account, new CreateWatchlistRequest("Old " + UUID.randomUUID()));
        watchlistsService.addInstrument(account, list.id(), new AddInstrumentRequest(symbol));

        String newName = "New " + UUID.randomUUID();
        assertEquals(newName, watchlistsService.renameWatchlist(account, list.id(), new RenameWatchlistRequest(newName)).name());

        watchlistsService.deleteWatchlist(account, list.id());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM watchlists WHERE id = ?", Integer.class, list.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM watchlist_items WHERE watchlist_id = ?", Integer.class, list.id()));
    }

    @Test
    void freshnessReportsTheLatestStoredPriceAndTheNextDueTime() {
        resolveSeed();
        marketDataService.ingestQuote(UUID.randomUUID(), quote("100.00"));

        MarketFreshnessResponse freshness = freshnessService.freshness();

        assertTrue(freshness.lastUpdatedOn() != null, "a price was just stored");
        assertEquals(freshness.lastUpdatedOn().plusSeconds(freshness.pollIntervalSeconds()), freshness.nextUpdateAt());
    }

    @Test
    void anInstrumentsFlagIsStoredAndReadBack() {
        resolveSeed();
        WatchlistResponse list = watchlistsService.createWatchlist(account, new CreateWatchlistRequest("Flags " + UUID.randomUUID()));
        watchlistsService.addInstrument(account, list.id(), new AddInstrumentRequest(symbol));

        WatchlistResponse flagged = watchlistsService.setItemFlag(account, list.id(), symbol, new SetItemFlagRequest("red"));
        assertEquals("RED", flagged.items().get(0).flag());

        WatchlistResponse cleared = watchlistsService.setItemFlag(account, list.id(), symbol, new SetItemFlagRequest(null));
        assertEquals(null, cleared.items().get(0).flag());
    }

    @Test
    void aReadyMadeListBecomesTheCustomersOwnList() {
        resolveSeed();
        WatchlistPresetResponse preset = watchlistsService.presets().get(0);

        WatchlistResponse created = watchlistsService.createFromPreset(account, preset.key());
        WatchlistResponse again = watchlistsService.createFromPreset(account, preset.key());

        assertEquals(preset.name(), created.name());
        assertEquals(preset.symbols(), created.items().stream().map(WatchlistItemResponse::symbol).toList());
        assertEquals(preset.name() + " 2", again.name(), "a second copy takes the next free name");
    }
}
