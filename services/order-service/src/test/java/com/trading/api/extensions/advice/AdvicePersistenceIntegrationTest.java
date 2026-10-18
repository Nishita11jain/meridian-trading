package com.trading.api.extensions.advice;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two advice mappers against real Postgres: an upsert followed by a read returns what was
 * written, a second upsert for the same symbol replaces rather than duplicates (both tables are
 * keyed by symbol alone), and findBySymbols returns only the symbols asked for.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class AdvicePersistenceIntegrationTest {

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @MockBean
    private OrderPublishReconciler reconciler;

    @Autowired
    private CandleCacheMapper candleCacheMapper;
    @Autowired
    private TradeSignalMapper tradeSignalMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(), "No reachable Postgres: skipping");
    }

    private String symbol() {
        return "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    @Test
    void candleCacheUpsertThenReadReturnsWhatWasWritten() {
        String symbol = symbol();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(24, ChronoUnit.HOURS);

        candleCacheMapper.upsert(symbol, "1d", "INR", "{\"data\":{\"candles\":[]}}", now, expiresAt);

        CandleCacheRow row = candleCacheMapper.findBySymbol(symbol);
        assertEquals(symbol, row.getSymbol());
        assertEquals("1d", row.getInterval());
        assertEquals(expiresAt, row.getExpiresAt());
    }

    @Test
    void candleCacheUpsertForTheSameSymbolReplacesRatherThanDuplicates() {
        String symbol = symbol();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        candleCacheMapper.upsert(symbol, "1d", "INR", "{\"first\":true}", now, now.plusSeconds(60));
        candleCacheMapper.upsert(symbol, "1d", "INR", "{\"second\":true}", now, now.plusSeconds(120));

        CandleCacheRow row = candleCacheMapper.findBySymbol(symbol);
        assertEquals("{\"second\":true}", row.getCandlesJson());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM candle_cache WHERE symbol = ?", Integer.class, symbol);
        assertEquals(1, count);
    }

    @Test
    void tradeSignalUpsertThenReadReturnsWhatWasWritten() {
        String symbol = symbol();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        tradeSignalMapper.upsert(symbol, "BUY", new BigDecimal("0.7200"), "test rationale",
                now, now.plus(24, ChronoUnit.HOURS));

        TradeSignalRow row = tradeSignalMapper.findBySymbol(symbol);
        assertEquals("BUY", row.getDirection());
        assertEquals(new BigDecimal("0.7200"), row.getStrength());
        assertEquals("test rationale", row.getRationale());
    }

    @Test
    void findBySymbolsReturnsOnlyTheSymbolsAskedFor() {
        String wanted = symbol();
        String notWanted = symbol();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        tradeSignalMapper.upsert(wanted, "BUY", new BigDecimal("0.5"), "r", now, now.plusSeconds(60));
        tradeSignalMapper.upsert(notWanted, "SELL", new BigDecimal("0.5"), "r", now, now.plusSeconds(60));

        List<TradeSignalRow> rows = tradeSignalMapper.findBySymbols(List.of(wanted));

        assertEquals(1, rows.size());
        assertEquals(wanted, rows.get(0).getSymbol());
    }

    @Test
    void aSymbolNeverCachedReadsAsNull() {
        assertNull(tradeSignalMapper.findBySymbol(symbol()));
        assertNull(candleCacheMapper.findBySymbol(symbol()));
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

    private static Connection connect() throws java.sql.SQLException {
        String host = env("POSTGRES_HOST", "localhost");
        String port = env("POSTGRES_PORT", "5432");
        String db = env("POSTGRES_DB", "");
        String user = env("POSTGRES_USER", "");
        String password = env("POSTGRES_PASSWORD", "");
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + db, user, password);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
