package com.trading.executor.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.event.OrderPlacedEvent;
import com.trading.executor.service.DeadLetterPublisher;
import com.trading.executor.service.OrderExecutionService;
import com.trading.executor.service.SettlementOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins design/kafka.md's "consumers ignore fields they do not recognise"
 * requirement: an additive change to the envelope or the payload must not
 * turn into a deserialization failure. Uses the same Jackson2ObjectMapperBuilder
 * Spring Boot wires as the ObjectMapper bean, not a bare `new ObjectMapper()`,
 * so this pins the behaviour actually running in production, not a stand-in.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacedConsumerUnknownFieldsTest {

    private final ObjectMapper objectMapper =
            Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private OrderExecutionService executionService;

    @Mock
    private FailureClassifier failureClassifier;

    @Mock
    private DeadLetterPublisher deadLetterPublisher;

    @Mock
    private Acknowledgment acknowledgment;

    @Test
    void consumeIgnoresUnrecognisedEnvelopeAndPayloadFields() throws Exception {

        OrderPlacedConsumer consumer =
                new OrderPlacedConsumer(objectMapper, executionService, failureClassifier, deadLetterPublisher);

        String message = """
                {
                  "eventId": "evt-1",
                  "eventType": "ORDER_PLACED",
                  "eventTime": "2026-09-16T10:00:00Z",
                  "source": "trade-api-service",
                  "schemaVersion": 1,
                  "traceId": "unrecognised-envelope-field",
                  "payload": {
                    "orderId": "11111111-1111-1111-1111-111111111111",
                    "accountId": 1,
                    "symbol": "INFY.NS",
                    "side": "BUY",
                    "quantity": 10,
                    "limitPrice": 100.00,
                    "idempotencyKey": "idem-key-001",
                    "clientTag": "unrecognised-payload-field"
                  }
                }
                """;

        // Mock successful settlement
        UUID orderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        SettlementOutcome outcome = SettlementOutcome.filled(orderId, 1L, null);
        when(executionService.execute(ArgumentCaptor.forClass(OrderPlacedEvent.Payload.class).capture()))
                .thenReturn(outcome);

        consumer.consume(message, acknowledgment, "account-1");

        ArgumentCaptor<OrderPlacedEvent.Payload> captor =
                ArgumentCaptor.forClass(OrderPlacedEvent.Payload.class);

        verify(executionService).execute(captor.capture());

        assertThat(captor.getValue().symbol()).isEqualTo("INFY.NS");
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("idem-key-001");
        verify(acknowledgment).acknowledge();
    }
}
