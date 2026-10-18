package com.trading.api.extensions.assistant;

import com.trading.domain.exception.DomainException;

/** The question is empty or longer than the assistant accepts. 422 VAL-422. */
public final class AssistantMessageException extends DomainException {

    public AssistantMessageException(String message) {
        super("VAL-422", message);
    }
}
