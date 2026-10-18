package com.trading.executor.consumer;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.trading.executor.exception.MalformedMessageException;
import com.trading.executor.exception.OrderNotFoundException;
import com.trading.executor.service.OrderSettlementRetryExhaustedException;
import com.trading.executor.service.TradeEventPublishException;
import com.trading.executor.exception.UnexpectedEventTypeException;
import com.trading.executor.service.OptimisticLockRetryException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefaultFailureClassifierTest {

    private final DefaultFailureClassifier classifier = new DefaultFailureClassifier();

    @Test
    void malformedJsonIsPermanentWithCorrectReason() {
        JsonMappingException exception = mock(JsonMappingException.class);
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("malformed-json");
    }

    @Test
    void malformedMessageWrappingAJacksonErrorIsMalformedJson() {
        MalformedMessageException exception = new MalformedMessageException(
                "Malformed JSON", new com.fasterxml.jackson.core.JsonParseException(null, "bad"));
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("malformed-json");
    }

    @Test
    void aSettledOrderWhoseEventCouldNotBePublishedIsRetriedAndHasItsOwnReason() {
        TradeEventPublishException exception =
                new TradeEventPublishException("Kafka publish timeout", new RuntimeException("timeout"));
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.TRANSIENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("trade-event-publish-failed");
    }

    @Test
    void exhaustedLockBudgetIsNotRetriedAgainAndKeepsItsReason() {
        OrderSettlementRetryExhaustedException exception =
                new OrderSettlementRetryExhaustedException("exhausted", new OptimisticLockRetryException("race"));
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("exhausted-retry-budget");
    }

    @Test
    void missingFieldIsPermanentWithGenericReason() {
        IllegalArgumentException exception = new IllegalArgumentException("Missing orderId");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("missing-required-field");
    }

    @Test
    void missingIdempotencyKeyGetsSpecificReason() {
        IllegalArgumentException exception = new IllegalArgumentException("missing idempotency key");
        assertThat(classifier.getFailureReason(exception)).isEqualTo("missing-idempotency-key");
    }

    @Test
    void orderNotFoundIsPermanent() {
        OrderNotFoundException exception = new OrderNotFoundException(UUID.randomUUID());
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("order-not-found");
    }

    @Test
    void unexpectedEventTypeIsPermanent() {
        UnexpectedEventTypeException exception = new UnexpectedEventTypeException("wrong type");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("unexpected-event-type");
    }

    @Test
    void optimisticLockRetryIsTransient() {
        OptimisticLockRetryException exception = new OptimisticLockRetryException("locked");
        assertThat(classifier.classify(exception, 3)).isEqualTo(FailureType.TRANSIENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("exhausted-retry-budget");
    }

    @Test
    void sqlExceptionIsTransient() {
        SQLException exception = new SQLException("connection lost");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.TRANSIENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("database-error");
    }

    @Test
    void kafkaTimeoutMessageIsTransientWithBrokerTimeoutReason() {
        RuntimeException exception = new RuntimeException("Kafka broker timeout occurred");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.TRANSIENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("broker-timeout");
    }

    @Test
    void unrecognizedRuntimeExceptionIsStillTransient() {
        RuntimeException exception = new RuntimeException("something totally unexpected");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.TRANSIENT);
        assertThat(classifier.getFailureReason(exception)).isEqualTo("unknown-error");
    }

    @Test
    void completelyUnknownThrowableDefaultsToTransient() {
        Throwable exception = new Error("never seen before");
        assertThat(classifier.classify(exception, 1)).isEqualTo(FailureType.TRANSIENT);
    }
}