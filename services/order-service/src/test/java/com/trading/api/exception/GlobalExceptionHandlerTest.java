package com.trading.api.exception;

import com.trading.api.dto.ErrorResponse;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void accountNotFoundMapsTo404() {

        AccountNotFoundException exception =
                new AccountNotFoundException(
                       
                        "account not found"
                );

        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(exception);

        assertEquals(
                HttpStatus.NOT_FOUND,
                response.getStatusCode()
        );

        assertEquals(
                new ErrorResponse(
                        "ACC-404",
                        "account not found"
                ),
                response.getBody()
        );
    }

    @Test
    void inactiveAccountMapsTo403() {

        AccountNotActiveException exception =
                new AccountNotActiveException(
                        
                        "account is not active"
                );

        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(exception);

        assertEquals(
                HttpStatus.FORBIDDEN,
                response.getStatusCode()
        );

        assertEquals(
                new ErrorResponse(
                        "ACC-403",
                        "account is not active"
                ),
                response.getBody()
        );
    }

    @Test
    void instrumentNotFoundMapsTo404() {

        InstrumentNotFoundException exception =
                new InstrumentNotFoundException(
                       
                        "instrument not found"
                );

        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(exception);

        assertEquals(
                HttpStatus.NOT_FOUND,
                response.getStatusCode()
        );

        assertEquals(
                new ErrorResponse(
                        "INS-404",
                        "instrument not found"
                ),
                response.getBody()
        );
    }

    @Test
    void unknownOrderMapsTo404WithTheContractCode() {
        ResponseEntity<ErrorResponse> response =
                handler.handleOrderNotFound(new OrderNotFoundException("Order not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(new ErrorResponse("ORD-409", "Order not found"), response.getBody());
    }

    @Test
    void anOrderThatIsNoLongerCancellableIsStillA409() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(new DuplicateOrderException("Order is not cancellable"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(new ErrorResponse("ORD-409", "Order is not cancellable"), response.getBody());
    }

    @Test
    void duplicateOrderMapsTo409() {

        DuplicateOrderException exception =
                new DuplicateOrderException(
                        
                        "duplicate order"
                );

        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(exception);

        assertEquals(
                HttpStatus.CONFLICT,
                response.getStatusCode()
        );

        assertEquals(
                new ErrorResponse(
                        "ORD-409",
                        "duplicate order"
                ),
                response.getBody()
        );
    }

    @Test
    void validationMapsTo422() {

        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(
                        new RuntimeException("test")
                );

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );
    }

    @Test
    void unexpectedExceptionReturnsGenericServerError() {

        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(
                        new RuntimeException("secret internal detail")
                );

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );

        assertEquals(
                new ErrorResponse(
                        "SRV-500",
                        "Internal server error"
                ),
                response.getBody()
        );
    }

    @Test
    void unknownCatalogCodeFailsLoudly() {

        assertThrows(
                IllegalStateException.class,
                () -> ErrorCatalog.statusFor("NOPE-000")
        );
    }

    @Test
void insufficientFundsMapsTo400() {
    var response = handler.handleDomainException(
            new InsufficientFundsException("insufficient funds for buy order",
                    new BigDecimal("500.00"), new BigDecimal("100.00")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().errorCode()).isEqualTo("ORD-400");
}
}