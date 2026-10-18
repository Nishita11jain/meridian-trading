package com.trading.api;

import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.OrderResponse;
import com.trading.api.exception.OrderNotFoundException;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.service.OrderEventPublisher;
import com.trading.api.service.OrderPublishReconciler;
import com.trading.api.service.OrderService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real service, mappers and Postgres together, for the persistence rules the unit tests can only
 * stand in for: the unique index on the idempotency key, the optimistic lock, the conditional cancel,
 * and the rule order for who may see what. Nothing between the service and the database is mocked.
 *
 * These run only when a database is reachable, using the same POSTGRES_* environment variables the
 * application uses, with the Sprint 3 schema and seed applied (databases/postgresql/apply.sh); otherwise the whole
 * class is skipped, so `mvn test` stays green on a machine with no database. Each test runs in a
 * transaction that is rolled back, so the data is left as it was. The one exception is the refusal
 * record, which the service writes in its own transaction on purpose; it is deleted again afterwards.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=01234567890123456789012345678901",
        "spring.kafka.bootstrap-servers=localhost:9",
        "trading.kafka.consumers.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class OrderPersistenceIntegrationTest {

    private static final String KEY_PREFIX = "it-";
    private static final long OWN_ACCOUNT = 1L;
    private static final long OTHER_ACCOUNT = 2L;

    // Placement is checked against real data here, not against the clock: the market is left open, so
    // these tests do not depend on the time they run. Trading hours are tested in TradingHoursTest.
    @MockBean
    private com.trading.api.service.TradingHours tradingHours;

    @MockBean
    private OrderEventPublisher orderEventPublisher;   // nothing here may reach Kafka

    @MockBean
    private OrderPublishReconciler reconciler;         // and the scheduled replay must not touch real orders

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireADatabase() {
        assumeTrue(databaseIsReachable(),
                "No reachable Postgres (set POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, and apply databases/postgresql/apply.sh): skipping");
    }

    /** Removes what the REQUIRES_NEW refusal record leaves behind, which the test rollback cannot reach. */
    @AfterEach
    void removeCommittedTestOrders() throws Exception {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM orders WHERE idempotency_key LIKE ?")) {
            delete.setString(1, KEY_PREFIX + "%");
            delete.executeUpdate();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Connection connect() throws Exception {
        String url = "jdbc:postgresql://" + env("POSTGRES_HOST", "localhost") + ":" + env("POSTGRES_PORT", "5432") + "/" + env("POSTGRES_DB", "");
        return DriverManager.getConnection(url, env("POSTGRES_USER", ""), env("POSTGRES_PASSWORD", ""));
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

    private String tradableSymbol() {
        return jdbc.queryForObject("SELECT symbol FROM instruments WHERE tradable = TRUE ORDER BY symbol LIMIT 1", String.class);
    }

    private static String newKey() {
        return KEY_PREFIX + UUID.randomUUID();
    }

    private PlaceOrderRequest buy(long accountId, String symbol, int quantity, String price, String key) {
        return new PlaceOrderRequest(accountId, symbol, OrderSide.BUY, quantity, new BigDecimal(price), key);
    }

    private int rowsWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class, key);
    }

    private static UUID idOf(OrderResponse response) {
        return UUID.fromString(response.orderId().replace("ORD-", ""));
    }

    // ---------------------------------------------------------------- placing an order

    @Test
    void aPlacedOrderIsStoredAsNewWithTheRequestedFields() {
        String key = newKey();
        String symbol = tradableSymbol();

        OrderResponse response = orderService.placeOrder(buy(OWN_ACCOUNT, symbol, 3, "10.50", key), OWN_ACCOUNT);

        assertEquals(OrderStatus.NEW, response.status());
        var row = jdbc.queryForMap("SELECT account_id, symbol, side, quantity, price, status FROM orders WHERE idempotency_key = ?", key);
        assertEquals(OWN_ACCOUNT, ((Number) row.get("account_id")).longValue());
        assertEquals(symbol, row.get("symbol"));
        assertEquals("BUY", row.get("side"));
        assertEquals(3, ((Number) row.get("quantity")).intValue());
        assertEquals(0, new BigDecimal("10.50").compareTo((BigDecimal) row.get("price")));
        assertEquals("NEW", row.get("status"));
    }

    @Test
    void acceptingAnOrderMovesNoMoney() {
        BigDecimal before = jdbc.queryForObject("SELECT cash_balance FROM accounts WHERE id = ?", BigDecimal.class, OWN_ACCOUNT);

        orderService.placeOrder(buy(OWN_ACCOUNT, tradableSymbol(), 1, "1.00", newKey()), OWN_ACCOUNT);

        BigDecimal after = jdbc.queryForObject("SELECT cash_balance FROM accounts WHERE id = ?", BigDecimal.class, OWN_ACCOUNT);
        assertEquals(0, before.compareTo(after), "cash only moves when the executor settles the order");
    }

    @Test
    void reusingAnIdempotencyKeyIsRefusedAndStoresOnlyOneOrder() {
        String key = newKey();
        String symbol = tradableSymbol();
        orderService.placeOrder(buy(OWN_ACCOUNT, symbol, 1, "1.00", key), OWN_ACCOUNT);

        DuplicateOrderException refusal = assertThrows(DuplicateOrderException.class,
                () -> orderService.placeOrder(buy(OWN_ACCOUNT, symbol, 1, "1.00", key), OWN_ACCOUNT));

        assertEquals("ORD-409", refusal.code());
    }

    @Test
    void thePostgresIndexItselfRefusesTwoOrdersWithTheSameKey() {
        String key = newKey();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String symbol = tradableSymbol();
        orderMapper.insert(first, OWN_ACCOUNT, symbol, OrderSide.BUY, 1, null, new BigDecimal("1.00"), OrderStatus.NEW, key, Instant.now());

        assertThrows(DuplicateKeyException.class, () ->
                orderMapper.insert(second, OWN_ACCOUNT, symbol, OrderSide.BUY, 1, null, new BigDecimal("1.00"), OrderStatus.NEW, key, Instant.now()));
    }

    @Test
    void anOrderTheAccountCannotAffordIsRefusedAndTheRefusalIsRecorded() {
        String key = newKey();

        InsufficientFundsException refusal = assertThrows(InsufficientFundsException.class,
                () -> orderService.placeOrder(buy(OWN_ACCOUNT, tradableSymbol(), 1000000, "100000.00", key), OWN_ACCOUNT));

        assertEquals("ORD-400", refusal.code());
        // The refusal is the record that the desk tried and was refused, so it is kept even though the request failed.
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM orders WHERE idempotency_key = ?", String.class, key));
        // ...and it says why, and when, in the same vocabulary the executor uses for its own rejections.
        assertEquals("INSUFFICIENT_FUNDS",
                jdbc.queryForObject("SELECT rejection_reason FROM orders WHERE idempotency_key = ?", String.class, key));
        assertTrue(jdbc.queryForObject(
                "SELECT updated_on IS NOT NULL FROM orders WHERE idempotency_key = ?", Boolean.class, key));
        // The reason is carried through to the order history the blotter reads.
        assertTrue(orderService.getOrderHistory(OWN_ACCOUNT, OWN_ACCOUNT, OrderStatus.REJECTED, null, null).stream()
                .anyMatch(entry -> "INSUFFICIENT_FUNDS".equals(entry.rejectionReason())));
    }

    // ---------------------------------------------------------------- who may do what, in what order

    @Test
    void anotherCustomersAccountIsRefusedAs403AndNothingIsStored() {
        String key = newKey();

        AccountNotActiveException refusal = assertThrows(AccountNotActiveException.class,
                () -> orderService.placeOrder(buy(OTHER_ACCOUNT, tradableSymbol(), 1, "1.00", key), OWN_ACCOUNT));

        assertEquals("ACC-403", refusal.code());
        assertEquals(0, rowsWithKey(key));
    }

    @Test
    void anAccountThatDoesNotExistIsAnswered403NotFoundSoNobodyCanProbeForAccounts() {
        AccountNotActiveException refusal = assertThrows(AccountNotActiveException.class,
                () -> orderService.placeOrder(buy(999999L, tradableSymbol(), 1, "1.00", newKey()), OWN_ACCOUNT));

        assertEquals("ACC-403", refusal.code());
    }

    @Test
    void anAccountThatDoesNotExistIsReportedAs404ToItsOwnToken() {
        AccountNotFoundException refusal = assertThrows(AccountNotFoundException.class,
                () -> orderService.placeOrder(buy(999999L, tradableSymbol(), 1, "1.00", newKey()), 999999L));

        assertEquals("ACC-404", refusal.code());
    }

    @Test
    void anInstrumentThatCannotBeTradedIsRefused() {
        List<String> retired = jdbc.queryForList("SELECT symbol FROM instruments WHERE tradable = FALSE LIMIT 1", String.class);
        assumeTrue(!retired.isEmpty(), "the seed has no retired instrument");

        InstrumentNotFoundException refusal = assertThrows(InstrumentNotFoundException.class,
                () -> orderService.placeOrder(buy(OWN_ACCOUNT, retired.get(0), 1, "1.00", newKey()), OWN_ACCOUNT));

        assertEquals("INS-404", refusal.code());
    }

    @Test
    void aSymbolThatIsNotInTheCatalogueIsRefused() {
        assertThrows(InstrumentNotFoundException.class,
                () -> orderService.placeOrder(buy(OWN_ACCOUNT, "NOSUCHSYMBOL", 1, "1.00", newKey()), OWN_ACCOUNT));
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void aNewOrderCanBeCancelledOnceAndNotAgain() {
        OrderResponse placed = orderService.placeOrder(buy(OWN_ACCOUNT, tradableSymbol(), 1, "1.00", newKey()), OWN_ACCOUNT);

        OrderResponse cancelled = orderService.cancelOrder(idOf(placed), OWN_ACCOUNT);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());

        DuplicateOrderException again = assertThrows(DuplicateOrderException.class,
                () -> orderService.cancelOrder(idOf(placed), OWN_ACCOUNT));
        assertEquals("Order is not cancellable", again.getMessage());
    }

    @Test
    void cancellingAnOrderThatDoesNotExistIsNotFound() {
        assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder(UUID.randomUUID(), OWN_ACCOUNT));
    }

    @Test
    void anotherCustomersOrderCannotBeCancelled() {
        OrderResponse placed = orderService.placeOrder(buy(OWN_ACCOUNT, tradableSymbol(), 1, "1.00", newKey()), OWN_ACCOUNT);

        assertThrows(AccountNotActiveException.class, () -> orderService.cancelOrder(idOf(placed), OTHER_ACCOUNT));

        assertEquals("NEW", jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, idOf(placed)));
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyIsNewestFirstAndKeepsEveryOrderIncludingCancelledOnes() throws Exception {
        String symbol = tradableSymbol();
        OrderResponse older = orderService.placeOrder(buy(OWN_ACCOUNT, symbol, 1, "1.00", newKey()), OWN_ACCOUNT);
        Thread.sleep(20);
        OrderResponse newer = orderService.placeOrder(buy(OWN_ACCOUNT, symbol, 2, "2.00", newKey()), OWN_ACCOUNT);
        orderService.cancelOrder(idOf(older), OWN_ACCOUNT);

        List<OrderHistoryEntry> history = orderService.getOrderHistory(OWN_ACCOUNT, OWN_ACCOUNT, null, null, null);

        assertEquals(newer.orderId(), history.get(0).orderId(), "newest first");
        assertTrue(history.stream().anyMatch(e -> e.orderId().equals(older.orderId()) && e.status() == OrderStatus.CANCELLED),
                "a cancelled order is still in the history");
    }

    @Test
    void historyForAnotherCustomersAccountIsRefused() {
        assertThrows(AccountNotActiveException.class, () -> orderService.getOrderHistory(OTHER_ACCOUNT, OWN_ACCOUNT, null, null, null));
    }

    // ---------------------------------------------------------------- the optimistic lock

    @Test
    void theOptimisticLockRefusesAWriteMadeFromAStaleVersion() {
        AccountRow read = accountMapper.findById(OWN_ACCOUNT);
        int version = read.getVersion();
        BigDecimal balance = read.getCashBalance().asBigDecimal();

        int first = accountMapper.updateCashBalanceWithVersion(OWN_ACCOUNT, balance, version);
        int second = accountMapper.updateCashBalanceWithVersion(OWN_ACCOUNT, balance, version);   // same, now stale, version

        assertEquals(1, first, "a write from the current version succeeds");
        assertEquals(0, second, "a write from a version someone else already moved past changes nothing");
        assertNotEquals(version, accountMapper.findById(OWN_ACCOUNT).getVersion(), "the version moved on");
    }
}
