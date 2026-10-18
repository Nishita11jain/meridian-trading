package com.trading.domain;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceOrderRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    private static PlaceOrderRequest validRequest() {
        return new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
    }

    private boolean violatesProperty(PlaceOrderRequest request, String propertyName) {
        Set<ConstraintViolation<PlaceOrderRequest>> violations = validator.validate(request);
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(propertyName));
    }

    // Valid request
    @Test
    void validRequestHasNoViolations() {
        assertTrue(validator.validate(validRequest()).isEmpty());
    }

    // Account id validations
    @Test
    void accountIdNullIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(null, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "accountId"));
    }

    @Test
    void accountIdBelowMinimumIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(0L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "accountId"));
    }

    @Test
    void accountIdAtMinimumIsAccepted() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertFalse(violatesProperty(request, "accountId"));
    }

    // Symbol validations
    @Test
    void symbolBlankIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "   ", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "symbol"));
    }

    @Test
    void symbolAtMaxLengthIsAccepted() {
        String symbol = "A".repeat(20);
        PlaceOrderRequest request = new PlaceOrderRequest(1L, symbol, OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertFalse(violatesProperty(request, "symbol"));
    }

    @Test
    void symbolAboveMaxLengthIsRejected() {
        String symbol = "A".repeat(21);
        PlaceOrderRequest request = new PlaceOrderRequest(1L, symbol, OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "symbol"));
    }

    // Side validation
    @Test
    void sideNullIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", null, 10, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "side"));
    }

    // Quantity Validations
    @Test
    void quantityZeroIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 0, new BigDecimal("100.00"), "12345678");
        assertTrue(violatesProperty(request, "quantity"));
    }

    @Test
    void quantityOfOneIsAccepted() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 1, new BigDecimal("100.00"), "12345678");
        assertFalse(violatesProperty(request, "quantity"));
    }

    // Price validations
    @Test
    void priceZeroIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("0.00"), "12345678");
        assertTrue(violatesProperty(request, "price"));
    }

    @Test
    void priceWithMoreThanTwoDecimalPlacesIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.001"), "12345678");
        assertTrue(violatesProperty(request, "price"));
    }

    @Test
    void priceOfOneCentIsAccepted() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("0.01"), "12345678");
        assertFalse(violatesProperty(request, "price"));
    }

    // Idempotency key validations
    @Test
    void idempotencyKeyBelowMinimumLengthIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "1234567");
        assertTrue(violatesProperty(request, "idempotencyKey"));
    }

    @Test
    void idempotencyKeyAtMinimumLengthIsAccepted() {
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), "12345678");
        assertFalse(violatesProperty(request, "idempotencyKey"));
    }

    @Test
    void idempotencyKeyAtMaximumLengthIsAccepted() {
        String key = "k".repeat(100);
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), key);
        assertFalse(violatesProperty(request, "idempotencyKey"));
    }

    @Test
    void idempotencyKeyAboveMaximumLengthIsRejected() {
        String key = "k".repeat(101);
        PlaceOrderRequest request = new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), key);
        assertTrue(violatesProperty(request, "idempotencyKey"));
    }
}
