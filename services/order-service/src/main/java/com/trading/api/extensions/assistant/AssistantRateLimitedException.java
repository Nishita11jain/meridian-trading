package com.trading.api.extensions.assistant;

import com.trading.domain.exception.DomainException;

/**
 * One account has sent more questions in a minute than the limit allows. 429 ASST-429. The limit exists
 * because the free Gemini quota is shared by every user of the platform.
 */
public final class AssistantRateLimitedException extends DomainException {

    public AssistantRateLimitedException(String message) {
        super("ASST-429", message);
    }
}
