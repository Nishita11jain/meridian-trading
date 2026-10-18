package com.trading.executor.service;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaDeadLetterPublisherTest {

	@Mock
	private KafkaTemplate<String, String> kafkaTemplate;

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void writesOriginalMessageToDltWithReasonHeaderAndWaitsForTheBroker() {
		when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

		new KafkaDeadLetterPublisher(kafkaTemplate)
				.publishDeadLetter("orders", "1", "{bad", "malformed-json", new IllegalStateException("boom"));

		ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafkaTemplate).send(captor.capture());

		ProducerRecord<String, String> record = captor.getValue();
		assertEquals("orders.DLT", record.topic());
		assertEquals("1", record.key());
		assertEquals("{bad", record.value());
		assertEquals("malformed-json",
				new String(record.headers().lastHeader("failure-reason").value(), StandardCharsets.UTF_8));
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void failedDeadLetterWriteThrowsSoTheCallerDoesNotAcknowledge() {
		when(kafkaTemplate.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

		KafkaDeadLetterPublisher publisher = new KafkaDeadLetterPublisher(kafkaTemplate);

		assertThrows(DeadLetterPublishException.class, () ->
				publisher.publishDeadLetter("orders", "1", "{bad", "malformed-json", new IllegalStateException("boom")));
	}
}
