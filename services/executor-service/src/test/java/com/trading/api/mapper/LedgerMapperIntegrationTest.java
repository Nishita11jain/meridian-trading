package com.trading.api.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ledger inserts against real Postgres: the SQL, the UUID handling and the table constraints (the
 * mocked settlement tests cannot check those). Everything runs in one transaction that is rolled back,
 * on a throwaway account and order, so no data is left behind. Self-skips with no database.
 */
class LedgerMapperIntegrationTest {

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void requireADatabase() {
        String db = env("POSTGRES_DB", "");
        String user = env("POSTGRES_USER", "");
        assumeTrue(!db.isEmpty() && !user.isEmpty(), "No Postgres configured: skipping");
        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {env("POSTGRES_HOST", "localhost")});
        dataSource.setPortNumbers(new int[] {Integer.parseInt(env("POSTGRES_PORT", "5432"))});
        dataSource.setDatabaseName(db);
        dataSource.setUser(user);
        dataSource.setPassword(env("POSTGRES_PASSWORD", ""));
        try (Connection probe = dataSource.getConnection()) {
            assumeTrue(probe.isValid(3), "No reachable Postgres: skipping");
        } catch (Exception unreachable) {
            assumeTrue(false, "No reachable Postgres: skipping");
        }
    }

    @Test
    void aFillIsRecordedAsATradeAndASignedLedgerRowReferencingTheOrder() throws Exception {
        Configuration configuration = new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(LedgerMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = factory.openSession(false)) {
            Connection connection = session.getConnection();
            long accountId = insertAccount(connection);
            UUID orderId = insertOrder(connection, accountId);
            Instant executedAt = Instant.parse("2026-10-01T09:30:00Z");

            LedgerMapper ledger = session.getMapper(LedgerMapper.class);
            assertEquals(1, ledger.insertTrade(orderId, 10, new BigDecimal("2075.00"), executedAt, null));
            assertEquals(1, ledger.insertCashMovement(accountId, "BUY", new BigDecimal("-20750.00"), orderId.toString(), executedAt));

            try (PreparedStatement trade = connection.prepareStatement(
                    "SELECT quantity, execution_price FROM trades WHERE order_id = ?")) {
                trade.setObject(1, orderId);
                ResultSet row = trade.executeQuery();
                assertTrue(row.next(), "the trade row exists");
                assertEquals(10, row.getInt(1));
                assertEquals(0, new BigDecimal("2075.00").compareTo(row.getBigDecimal(2)));
            }
            try (PreparedStatement cash = connection.prepareStatement(
                    "SELECT transaction_type, amount, reference_type FROM account_transactions WHERE reference_id = ?")) {
                cash.setString(1, orderId.toString());
                ResultSet row = cash.executeQuery();
                assertTrue(row.next(), "the ledger row exists");
                assertEquals("BUY", row.getString(1));
                assertEquals(0, new BigDecimal("-20750.00").compareTo(row.getBigDecimal(2)), "a buy is money out");
                assertEquals("ORDER", row.getString(3));
            }
            session.rollback(true);
        }
    }

    private static long insertAccount(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO accounts (account_id, holder_name, cash_balance, status) VALUES (?, 'LedgerIT', 100000, 'ACTIVE') RETURNING id")) {
            insert.setString(1, "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
            ResultSet row = insert.executeQuery();
            row.next();
            return row.getLong(1);
        }
    }

    private static UUID insertOrder(Connection connection, long accountId) throws Exception {
        UUID id = UUID.randomUUID();
        String symbol;
        try (PreparedStatement pick = connection.prepareStatement("SELECT symbol FROM instruments WHERE tradable = TRUE ORDER BY symbol LIMIT 1")) {
            ResultSet row = pick.executeQuery();
            row.next();
            symbol = row.getString(1);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO orders (id, account_id, symbol, side, quantity, price, status, idempotency_key) "
                        + "VALUES (?, ?, ?, 'BUY', 10, 2075.00, 'NEW', ?)")) {
            insert.setObject(1, id);
            insert.setLong(2, accountId);
            insert.setString(3, symbol);
            insert.setString(4, "ledger-it-" + id);
            insert.executeUpdate();
        }
        return id;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
