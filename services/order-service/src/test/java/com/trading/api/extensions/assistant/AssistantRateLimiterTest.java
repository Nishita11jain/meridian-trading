package com.trading.api.extensions.assistant;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void the_limit_applies_per_account() {
        AssistantRateLimiter limiter = new AssistantRateLimiter(2, new MutableClock(Instant.parse("2026-10-05T10:00:00Z")));

        limiter.acquire(1L);
        limiter.acquire(1L);

        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(AssistantRateLimitedException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((AssistantRateLimitedException) e).code()).isEqualTo("ASST-429"));
        assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException();
    }

    @Test
    void the_window_slides_so_the_account_is_allowed_again() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        AssistantRateLimiter limiter = new AssistantRateLimiter(1, clock);

        limiter.acquire(1L);
        clock.advance(java.time.Duration.ofSeconds(61));

        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }
}
