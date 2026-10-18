package com.trading.domain;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PlaceOrderRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validRequestShouldPassValidation() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                10,
                new BigDecimal("100.00"),
            "key-1234"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullAccountIdShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                "TCS.NS",
                OrderSide.BUY,
                10,
                new BigDecimal("100.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void blankSymbolShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "",
                OrderSide.BUY,
                10,
                new BigDecimal("100.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void nullSideShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                null,
                10,
                new BigDecimal("100.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void zeroQuantityShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                0,
                new BigDecimal("100.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void negativeQuantityShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                -5,
                new BigDecimal("100.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void priceBelowMinimumShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                10,
                new BigDecimal("0.00"),
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void nullPriceShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                10,
                null,
                "key-123"
        );

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void blankIdempotencyKeyShouldFail() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                1L,
                "TCS.NS",
                OrderSide.BUY,
                10,
                new BigDecimal("100.00"),
                ""
        );

        assertFalse(validator.validate(request).isEmpty());
    }
}