package com.trading.api.extensions.assistant;

/** One question from the signed-in user. The account is never taken from here: it comes from the token. */
public record ChatRequest(String message) {
}
