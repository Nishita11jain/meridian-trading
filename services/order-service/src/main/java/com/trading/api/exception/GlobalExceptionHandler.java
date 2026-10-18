package com.trading.api.exception;

import com.trading.api.dto.ErrorResponse;
import com.trading.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException exception) {
        HttpStatus status =ErrorCatalog.statusFor(exception.code());
        log.info(
                "Domain exception: code={}, message={}",
                exception.code(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(status)
                .body(new ErrorResponse(
                        exception.code(),
                        exception.getMessage()
                ));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException exception) {
        log.info("Order not found: {}", exception.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        OrderNotFoundException.CODE,
                        exception.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(
            MethodArgumentNotValidException exception) {

        log.info("Request validation failed");

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(
                        "VAL-422",
                        "Invalid input"
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {

        log.info("Invalid request parameter");

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(
                        "VAL-422",
                        "Invalid input"
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error(
                "Unexpected server error",
                exception
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        "SRV-500",
                        "Internal server error"
                ));
    }

        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(
            org.springframework.http.converter.HttpMessageNotReadableException exception) {
        log.info("Malformed request body");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("VAL-422", "Invalid input"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleUnknownRoute(
            org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        log.info("No handler for this route");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ROUTE-404", "Not found"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleWrongVerb(
            org.springframework.web.HttpRequestMethodNotSupportedException exception) {
        log.info("HTTP method not supported for this route");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("ROUTE-405", "Method not allowed"));
    }
}