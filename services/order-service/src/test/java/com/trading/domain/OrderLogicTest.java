package com.trading.domain;

import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.exception.InvalidOrderParametersException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class OrderLogicTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final String SYMBOL = "INFY.NS";

    private final Set<String> usedKeys = new HashSet<>();
    private final DuplicateOrderChecker duplicateOrderChecker = usedKeys::contains;
    private final OrderPlacementService service = new OrderPlacementService(duplicateOrderChecker);

    private Account activeAccount() {
        return activeAccount("10000.00");
    }

    private Account activeAccount(String cash) {
        return new Account(ACCOUNT_ID, "REF-1", "Holder", Money.of(cash), AccountStatus.ACTIVE, 0);
    }

    private Instrument tradableInstrument() {
        return new Instrument(SYMBOL, "Infosys", "EQUITY", "INR", true);
    }

    private PlaceOrderRequest request(OrderSide side, int quantity, String price, String idempotencyKey) {
        return new PlaceOrderRequest(ACCOUNT_ID, SYMBOL, side, quantity, new BigDecimal(price), idempotencyKey);
    }

    // Buy and sell validations
    @Test
    void buySucceedsAsNewWithoutMovingCashOrPosition() {
        // Cash and the position move once, at settlement, not here -- moving
        // them at acceptance too would debit/credit every order twice.
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-buy-1");

        Order order = service.placeOrder(account, tradableInstrument(), position, request);

        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(Money.of("10000.00"), account.getCashBalance());
        assertEquals(0L, position.getQuantity());
        assertEquals(Money.ZERO, position.getAverageCost());
    }

    @Test
    void buyRejectedOnInsufficientFunds() {
        Account account = activeAccount("50.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-buy-2");

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("ORD-400", ex.code());
    }

    @Test
    void sellRejectedOnInsufficientHoldings() {
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 5, Money.of("100.00"), 0);
        PlaceOrderRequest request = request(OrderSide.SELL, 10, "100.00", "idem-key-sell-1");

        InsufficientHoldingsException ex = assertThrows(InsufficientHoldingsException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("ORD-409", ex.code());
    }

    // Account Exists or not 
    @Test
    void orderRejectedOnAccountThatDoesNotExist() {
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-noacc");

        AccountNotFoundException ex = assertThrows(AccountNotFoundException.class,
                () -> service.placeOrder(null, tradableInstrument(), position, request));
        assertEquals("ACC-404", ex.code());
    }

    // Order rejected when the account is not active.
    @Test
    void orderRejectedOnInactiveAccount() {
        Account account = new Account(ACCOUNT_ID, "REF-1", "Holder", Money.of("10000.00"), AccountStatus.SUSPENDED, 0);
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-inactive");

        AccountNotActiveException ex = assertThrows(AccountNotActiveException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("ACC-403", ex.code());
    }

    // Order validation on invalid instrument
    @Test
    void orderRejectedOnUnknownOrNonTradableInstrument() {
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);

        InstrumentNotFoundException exUnknown = assertThrows(InstrumentNotFoundException.class,
                () -> service.placeOrder(account, null, position,
                        request(OrderSide.BUY, 10, "100.00", "idem-key-noins-1")));
        assertEquals("INS-404", exUnknown.code());

        Instrument delisted = tradableInstrument();
        delisted.delist();
        InstrumentNotFoundException exDelisted = assertThrows(InstrumentNotFoundException.class,
                () -> service.placeOrder(account, delisted, position,
                        request(OrderSide.BUY, 10, "100.00", "idem-key-noins-2")));
        assertEquals("INS-404", exDelisted.code());
    }

    // Order validation on duplicate idempotency key
    @Test
    void duplicateIdempotencyKeyRejected() {
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        usedKeys.add("idem-key-dup");
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-dup");

        DuplicateOrderException ex = assertThrows(DuplicateOrderException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("ORD-409", ex.code());
    }

    // Rule priority check
    @Test
    void requestBreakingTwoRulesReturnsCodeOfTheFirst() {
        Account account = new Account(ACCOUNT_ID, "REF-1", "Holder", Money.ZERO, AccountStatus.SUSPENDED, 0);
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "100.00", "idem-key-twobreaks");

        AccountNotActiveException ex = assertThrows(AccountNotActiveException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("ACC-403", ex.code());
    }

    // Order validation on negative sell
    @Test
    void quantityMustBeGreaterThanZero() {
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 0, "100.00", "idem-key-qty-zero");

        InvalidOrderParametersException ex = assertThrows(InvalidOrderParametersException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("VAL-422", ex.code());
    }

    // Order validation on negative price
    @Test
    void priceMustBeGreaterThanZero() {
        Account account = activeAccount("10000.00");
        Position position = new Position(ACCOUNT_ID, SYMBOL, 0, Money.ZERO, 0);
        PlaceOrderRequest request = request(OrderSide.BUY, 10, "0.00", "idem-key-price-zero");

        InvalidOrderParametersException ex = assertThrows(InvalidOrderParametersException.class,
                () -> service.placeOrder(account, tradableInstrument(), position, request));
        assertEquals("VAL-422", ex.code());
    }

        @Test
    void ruleOneDoesNotFireForAGenuineAccount() {
        // Confirms rule 1 (account == null) only refuses a missing account --
        // a real, active account with a valid order must not be rejected
        // for this reason. If some other rule fires instead, that's a
        // different, real failure -- assertDoesNotThrow makes sure THIS
        // specific case (a genuine account) never gets treated as missing.
        Account account = new Account(ACCOUNT_ID, "ETP-4101", "Holder",
                Money.of("10000.00"), AccountStatus.ACTIVE, 0);
        Instrument instrument = new Instrument(SYMBOL, "Test Instrument", "EQUITY", "INR", true);
        Position position = Position.openNew(ACCOUNT_ID, SYMBOL);
        PlaceOrderRequest request = new PlaceOrderRequest(ACCOUNT_ID, SYMBOL, OrderSide.BUY, 10,
                new BigDecimal("100.00"), "key-rule1-not-firing-001");
        OrderPlacementService service = new OrderPlacementService(key -> false);

        assertDoesNotThrow(() -> service.placeOrder(account, instrument, position, request));
    }

    @Test
    void sellAcceptedAsNewLeavesPositionUntouchedUntilSettlement() {
        Position position = Position.openNew(ACCOUNT_ID, SYMBOL);
        position.applyBuy(100, Money.of("50.00"));
        Account account = activeAccount();
        Instrument instrument = new Instrument(SYMBOL, "Test", "EQUITY", "INR", true);
        PlaceOrderRequest sellRequest = new PlaceOrderRequest(ACCOUNT_ID, SYMBOL, OrderSide.SELL, 40,
                new BigDecimal("55.00"), "key-sell-happy-001");
        OrderPlacementService service = new OrderPlacementService(key -> false);

        Order order = service.placeOrder(account, instrument, position, sellRequest);

        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(100, position.getQuantity());
        assertEquals(Money.of("50.00"), position.getAverageCost());
    }

    @Test
    void buyAcceptedAsNewLeavesPositionUntouchedUntilSettlement() {
        Position position = Position.openNew(ACCOUNT_ID, SYMBOL);
        position.applyBuy(100, Money.of("50.00"));
        Account account = activeAccount();
        Instrument instrument = new Instrument(SYMBOL, "Test", "EQUITY", "INR", true);
        PlaceOrderRequest buyRequest = new PlaceOrderRequest(ACCOUNT_ID, SYMBOL, OrderSide.BUY, 50,
                new BigDecimal("56.00"), "key-buy-weighted-001");
        OrderPlacementService service = new OrderPlacementService(key -> false);

        Order order = service.placeOrder(account, instrument, position, buyRequest);

        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(100, position.getQuantity());
        assertEquals(Money.of("50.00"), position.getAverageCost());
    }
}
