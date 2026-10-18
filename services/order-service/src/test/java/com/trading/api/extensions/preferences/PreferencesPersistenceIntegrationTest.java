package com.trading.api.extensions.preferences;

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

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The preferences mapper against real Postgres: that the two upserts each touch only their own
 * columns (saving the display settings must not wipe the notification settings), and that a read
 * returns what was written. Runs only when a database is reachable; each test is rolled back, so the
 * row it writes for a seeded account leaves nothing behind.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class PreferencesPersistenceIntegrationTest {

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @MockBean
    private OrderPublishReconciler reconciler;

    @Autowired
    private PreferencesMapper preferencesMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long seededAccount;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(), "No reachable Postgres: skipping");
    }

    // A throwaway account created inside the test transaction (rolled back with it), so "this account has
    // stored nothing" holds regardless of what live usage has written to customer_preferences.
    void resolveSeededAccount() {
        seededAccount = jdbc.queryForObject(
                "INSERT INTO accounts (account_id, holder_name, cash_balance, status) "
                        + "VALUES (?, 'IntegrationTest', 100000, 'ACTIVE') RETURNING id",
                Long.class, "IT" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 28));
    }

    @Test
    void savingDisplayThenNotificationKeepsBothSetsOfSettings() {
        resolveSeededAccount();

        preferencesMapper.upsertDisplay(seededAccount, seededAccount, "USD", "DARK");
        preferencesMapper.upsertNotification(seededAccount, seededAccount, "EMAIL", "a@b.com", null);

        CustomerPreferenceRow row = preferencesMapper.findByAccountId(seededAccount);
        assertEquals("USD", row.getDisplayCurrency(), "the notification upsert must not reset the display settings");
        assertEquals("DARK", row.getTheme());
        assertEquals("EMAIL", row.getNotificationChannel());
        assertEquals("a@b.com", row.getContactEmail());
    }

    @Test
    void savingNotificationThenDisplayKeepsBothSetsOfSettings() {
        resolveSeededAccount();

        preferencesMapper.upsertNotification(seededAccount, seededAccount, "SMS", null, "+91999");
        preferencesMapper.upsertDisplay(seededAccount, seededAccount, "INR", "LIGHT");

        CustomerPreferenceRow row = preferencesMapper.findByAccountId(seededAccount);
        assertEquals("SMS", row.getNotificationChannel(), "the display upsert must not reset the notification settings");
        assertEquals("+91999", row.getContactPhone());
        assertEquals("INR", row.getDisplayCurrency());
        assertEquals("LIGHT", row.getTheme());
    }

    @Test
    void aSecondUpsertUpdatesInPlaceRatherThanDuplicating() {
        resolveSeededAccount();

        preferencesMapper.upsertDisplay(seededAccount, seededAccount, "INR", "LIGHT");
        preferencesMapper.upsertDisplay(seededAccount, seededAccount, "USD", "DARK");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM customer_preferences WHERE account_id = ?", Integer.class, seededAccount);
        assertEquals(1, rows, "account_id is the primary key, so an upsert updates one row rather than adding another");
        assertEquals("USD", preferencesMapper.findByAccountId(seededAccount).getDisplayCurrency());
    }

    @Test
    void readingAnAccountThatHasStoredNothingReturnsNull() {
        resolveSeededAccount();

        assertNull(preferencesMapper.findByAccountId(seededAccount),
                "no row means the service falls back to defaults, rather than the mapper inventing one");
    }

    private static Connection connect() throws Exception {
        String host = env("POSTGRES_HOST", "localhost");
        String port = env("POSTGRES_PORT", "5432");
        String db = env("POSTGRES_DB", "");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + db,
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
}
