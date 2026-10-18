package com.trading.api.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

final class ErrorCatalog {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry("ACC-404", HttpStatus.NOT_FOUND),
            Map.entry("ACC-403", HttpStatus.FORBIDDEN),
            Map.entry("INS-404", HttpStatus.NOT_FOUND),
            Map.entry("ORD-400", HttpStatus.BAD_REQUEST),
            Map.entry("ORD-409", HttpStatus.CONFLICT),
            Map.entry("VAL-422", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("AUTH-401", HttpStatus.UNAUTHORIZED),
            // Sprint 10 extensions
            Map.entry("WL-404", HttpStatus.NOT_FOUND),
            Map.entry("WL-409", HttpStatus.CONFLICT),
            Map.entry("ALERT-404", HttpStatus.NOT_FOUND),
            Map.entry("LIMIT-429", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("MKT-503", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("NTF-404", HttpStatus.NOT_FOUND),
            Map.entry("MKT-409", HttpStatus.CONFLICT),
            Map.entry("ASST-503", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("ASST-429", HttpStatus.TOO_MANY_REQUESTS),
            // Trade Advice & Signals (Sprint 10 stretch extension)
            Map.entry("ADV-404", HttpStatus.NOT_FOUND),
            Map.entry("ADV-503", HttpStatus.SERVICE_UNAVAILABLE),
            // Automated Strategy Execution (Sprint 10 stretch extension)
            Map.entry("STRAT-404", HttpStatus.NOT_FOUND)
    );

    private ErrorCatalog() {
    }

    static HttpStatus statusFor(String code) {
        HttpStatus status = STATUS_BY_CODE.get(code);

        if (status == null) {
            throw new IllegalStateException(
                    "No HTTP status mapped for catalogue code: " + code
            );
        }

        return status;
    }
}