package com.trading.api.extensions.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding one-minute limit per account for the assistant. The Gemini free quota is shared by every user,
 * so one account must not be able to spend all of it. In memory and per instance: enough for the
 * single-instance deployment this project runs.
 */
@Component
public class AssistantRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limitPerMinute;
    private final Clock clock;
        @Autowired
    public AssistantRateLimiter(@Value("${assistant.rate-limit-per-minute:10}") int limitPerMinute) {
        this(limitPerMinute, Clock.systemUTC());
    }

    private final Map<Long, Deque<Instant>> sentByAccount = new HashMap<>();


    AssistantRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** Records one question for the account, or throws ASST-429 when the account is over the limit. */
    public synchronized void acquire(long accountId) {
        Instant now = clock.instant();
        Deque<Instant> sent = sentByAccount.computeIfAbsent(accountId, id -> new ArrayDeque<>());
        while (!sent.isEmpty() && sent.peekFirst().isBefore(now.minus(WINDOW))) {
            sent.pollFirst();
        }
        if (sent.size() >= limitPerMinute) {
            throw new AssistantRateLimitedException(
                    "You have asked the assistant " + limitPerMinute + " questions in the last minute. Try again shortly.");
        }
        sent.addLast(now);
    }
}
