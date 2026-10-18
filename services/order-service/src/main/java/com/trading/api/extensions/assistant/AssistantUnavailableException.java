package com.trading.api.extensions.assistant;

import com.trading.domain.exception.DomainException;

/**
 * The assistant cannot answer right now: no Gemini key is configured, or the model call failed or timed
 * out. 503 ASST-503. The UI shows a plain message and the rest of the platform is unaffected.
 */
public final class AssistantUnavailableException extends DomainException {

    public AssistantUnavailableException(String message) {
        super("ASST-503", message);
    }
}
