package com.trading.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaConsumerConfigTest {

    @Test
    void aMessageThatKeepsFailingGoesToTheDeadLetterTopicOfItsOwnTopic() {
        TopicPartition target = KafkaConsumerConfig.deadLetterDestination(
                new ConsumerRecord<>("trade-events", 2, 40L, "k", "{}"), new RuntimeException("boom"));

        assertThat(target.topic()).isEqualTo("trade-events.DLT");
        assertThat(target.partition()).isNegative();
    }

    @Test
    void marketDataGetsItsOwnDeadLetterTopic() {
        TopicPartition target = KafkaConsumerConfig.deadLetterDestination(
                new ConsumerRecord<>("market-data", 0, 1L, "AAPL", "{}"), new RuntimeException("boom"));

        assertThat(target.topic()).isEqualTo("market-data.DLT");
    }
}
