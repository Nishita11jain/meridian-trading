package com.trading.api.dto;

/**
 * The single error envelope for every failure, platform-wide. Clients
 * branch on errorCode, never on message and never on the HTTP status
 * alone, since 404 and 409 each carry two codes.
 */
public record ErrorResponse(String errorCode, String message) {
}
