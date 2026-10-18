package com.trading.executor.service;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Kafka implementation of DeadLetterPublisher.
 * Routes messages to dead-letter topics and adds failure metadata in headers.
 */
@Repository
public class KafkaDeadLetterPublisher implements DeadLetterPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterPublisher.class);

	private static final long SEND_TIMEOUT_SECONDS = 5;

	private final KafkaTemplate<String, String> kafkaTemplate;

	public KafkaDeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@Override
	public void publishDeadLetter(
			String originalTopic,
			String key,
			String originalMessage,
			String failureReason,
			Throwable cause) {

		String deadLetterTopic = getDLTopic(originalTopic);

		try {
			// Create ProducerRecord with headers
			ProducerRecord<String, String> record = new ProducerRecord<>(
					deadLetterTopic,
					null,  // partition
					key,
					originalMessage
			);

			// Add failure metadata as headers
			record.headers()
					.add(new RecordHeader("failure-reason", failureReason.getBytes(StandardCharsets.UTF_8)))
					.add(new RecordHeader("failure-timestamp", Instant.now().toString().getBytes(StandardCharsets.UTF_8)))
					.add(new RecordHeader("failure-class", cause.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8)));

			// Wait for the broker: the caller acknowledges the original message as soon
			// as this returns, so an unconfirmed send would lose it.
			kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			log.info(
					"Message dead-lettered: topic={}, reason={}, key={}, error={}",
					originalTopic, failureReason, key, cause.getMessage()
			);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new DeadLetterPublishException(
					"Interrupted while dead-lettering a message from " + originalTopic, exception);
		} catch (Exception exception) {
			log.error(
					"Failed to publish dead-letter message for topic {} with reason {}",
					originalTopic, failureReason, exception
			);
			// Throw so the caller does not acknowledge: the original message is the
			// only copy left, and it will be redelivered.
			throw new DeadLetterPublishException(
					"Could not write to " + deadLetterTopic + " (reason " + failureReason + ")", exception);
		}
	}

	/**
	 * Map topic name to its dead-letter topic.
	 * orders → orders.DLT
	 * trade-events → trade-events.DLT
	 * etc.
	 */
	private String getDLTopic(String originalTopic) {
		return originalTopic + ".DLT";
	}
}
