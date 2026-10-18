package com.trading.api.extensions.notifications;

import com.trading.api.extensions.ProcessedEventsMapper;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The notifications and processed-events mappers against real Postgres: the unique source_event_id
 * makes a second insert of the same event a no-op, the idempotency claim is won once, and the inbox
 * reads newest-first with its unread count. Self-skips with no database; each test is rolled back.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class NotificationsPersistenceIntegrationTest {

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @MockBean
    private OrderPublishReconciler reconciler;

    @Autowired
    private NotificationsMapper notificationsMapper;
    @Autowired
    private ProcessedEventsMapper processedEventsMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long account;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(), "No reachable Postgres: skipping");
    }

    // A throwaway account created inside the test transaction (and rolled back with it), so these
    // assertions about an account's inbox hold no matter what live usage has left in the shared tables.
    void resolveAccount() {
        account = jdbc.queryForObject(
                "INSERT INTO accounts (account_id, holder_name, cash_balance, status) "
                        + "VALUES (?, 'IntegrationTest', 100000, 'ACTIVE') RETURNING id",
                Long.class, "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
    }

    private int insert(UUID sourceEvent, String subject) {
        return notificationsMapper.insertIfNew(UUID.randomUUID(), account, sourceEvent,
                "ORDER_FILLED", "IN_APP", subject, "body", "SENT", null, null, null);
    }

    @Test
    void theSameSourceEventNeverProducesTwoNotifications() {
        resolveAccount();
        UUID event = UUID.randomUUID();

        assertEquals(1, insert(event, "first"), "the first insert creates the row");
        assertEquals(0, insert(event, "second"), "a second insert for the same source event creates nothing");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE source_event_id = ?", Integer.class, event);
        assertEquals(1, rows);
    }

    @Test
    void theIdempotencyClaimIsWonOncePerConsumer() {
        UUID event = UUID.randomUUID();

        assertEquals(1, processedEventsMapper.claim("notification-service", event), "first claim wins");
        assertEquals(0, processedEventsMapper.claim("notification-service", event), "a redelivery claims nothing");
        // A different consumer name is independent, so the watchlist consumer could still process it.
        assertEquals(1, processedEventsMapper.claim("watchlist-service", event));
    }

    @Test
    void theInboxReadsNewestFirstAndCountsUnread() {
        resolveAccount();
        insert(UUID.randomUUID(), "older");
        insert(UUID.randomUUID(), "newer");
        // created_on defaults to NOW(), which is constant within one transaction, so set the two rows'
        // timestamps explicitly to make "newest first" deterministic rather than an id tiebreaker.
        jdbc.update("UPDATE notifications SET created_on = ? WHERE account_id = ? AND subject = 'older'",
                java.sql.Timestamp.valueOf("2026-10-01 09:00:00"), account);
        jdbc.update("UPDATE notifications SET created_on = ? WHERE account_id = ? AND subject = 'newer'",
                java.sql.Timestamp.valueOf("2026-10-01 10:00:00"), account);

        List<NotificationRow> inbox = notificationsMapper.findByAccount(account);
        assertEquals("newer", inbox.get(0).getSubject(), "newest first");
        assertEquals(2, notificationsMapper.countUnread(account), "nothing read yet");
    }

    @Test
    void markingAllReadClearsTheUnreadCountAndStampsReadOn() {
        resolveAccount();
        insert(UUID.randomUUID(), "one");
        insert(UUID.randomUUID(), "two");

        int updated = notificationsMapper.markAllRead(account);

        assertEquals(2, updated);
        assertEquals(0, notificationsMapper.countUnread(account));
        for (NotificationRow row : notificationsMapper.findByAccount(account)) {
            org.junit.jupiter.api.Assertions.assertNotNull(row.getReadOn(), "a read notification carries a readOn");
        }
    }

    @Test
    void anAccountWithNoNotificationsReadsEmptyWithZeroUnread() {
        resolveAccount();

        assertEquals(List.of(), notificationsMapper.findByAccount(account));
        assertEquals(0, notificationsMapper.countUnread(account));
        assertNull(jdbc.queryForObject(
                "SELECT max(created_on) FROM notifications WHERE account_id = ?", java.sql.Timestamp.class, account));
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
    void theReferencesAreStoredAndReadBack() {
        resolveAccount();
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        notificationsMapper.insertIfNew(id, account, UUID.randomUUID(), "ORDER_REJECTED", "IN_APP",
                "Order rejected: TCS.NS", "body", "SENT", order, null, "TCS.NS");

        NotificationRow row = notificationsMapper.findOne(account, id);
        assertEquals(order, row.getOrderId());
        assertNull(row.getAlertId());
        assertEquals("TCS.NS", row.getSymbol());
        assertEquals(order, notificationsMapper.findByAccount(account).get(0).getOrderId());
    }

    @Test
    void findOneDoesNotReturnAnotherAccountsNotification() {
        resolveAccount();
        UUID id = UUID.randomUUID();
        notificationsMapper.insertIfNew(id, account, UUID.randomUUID(), "PRICE_ALERT_TRIGGERED", "IN_APP",
                "Alert", "body", "SENT", null, 7L, "TCS.NS");

        assertEquals(7L, notificationsMapper.findOne(account, id).getAlertId());
        assertNull(notificationsMapper.findOne(account + 1_000_000L, id));
        assertEquals(0, notificationsMapper.markRead(account + 1_000_000L, id), "another account cannot mark it");
    }

    @Test
    void markingOneReadOrUnreadChangesOnlyThatOne() {
        resolveAccount();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        notificationsMapper.insertIfNew(first, account, UUID.randomUUID(), "ORDER_FILLED", "IN_APP", "one", "b", "SENT", null, null, null);
        notificationsMapper.insertIfNew(second, account, UUID.randomUUID(), "ORDER_FILLED", "IN_APP", "two", "b", "SENT", null, null, null);

        assertEquals(1, notificationsMapper.markRead(account, first));
        assertEquals(1, notificationsMapper.countUnread(account), "only that one was read");
        org.junit.jupiter.api.Assertions.assertNotNull(notificationsMapper.findOne(account, first).getReadOn());
        assertNull(notificationsMapper.findOne(account, second).getReadOn());
        assertEquals(0, notificationsMapper.markRead(account, first), "reading it again keeps the first read time");

        assertEquals(1, notificationsMapper.markUnread(account, first));
        assertEquals(2, notificationsMapper.countUnread(account));
        assertNull(notificationsMapper.findOne(account, first).getReadOn());
    }
}
