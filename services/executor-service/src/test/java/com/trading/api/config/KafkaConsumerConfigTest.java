package com.trading.api.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The executor's consumer settings are not tuning, they are rules from contracts/event-schemas/kafka-topics.md:
 * an explicit group id (so this consumer's offsets are its own), auto-commit off (an offset must only
 * move after the order is settled, so a crash re-delivers instead of losing the order), and manual
 * acknowledgement. A change to any of them would not fail loudly: orders would just be lost or
 * executed twice under the right failure. So each is asserted here.
 */
class KafkaConsumerConfigTest {

    private KafkaConsumerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "broker.test:9092");
    }

    @Test
    void usesTheExecutorsOwnGroupId() {
        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals("trade-executor", props.get(ConsumerConfig.GROUP_ID_CONFIG));
    }

    @Test
    void doesNotCommitOffsetsAutomatically() {
        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals(false, props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void startsFromTheEarliestOffsetWhenItHasNoSavedPosition() {
        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void connectsToTheConfiguredBroker() {
        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals("broker.test:9092", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void readsKeysAndValuesAsPlainStrings() {
        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals("org.apache.kafka.common.serialization.StringDeserializer", props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.StringDeserializer", props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
    }

    @Test
    void theListenerAcknowledgesManuallyAfterTheOrderIsSettled() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = config.kafkaListenerContainerFactory();

        assertEquals(ContainerProperties.AckMode.MANUAL, factory.getContainerProperties().getAckMode());
    }

    @Test
    void theListenerFactoryUsesTheConsumerFactoryAboveSoTheSettingsApply() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = config.kafkaListenerContainerFactory();
        ConsumerFactory<?, ?> used = factory.getConsumerFactory();

        assertEquals("trade-executor", used.getConfigurationProperties().get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(false, used.getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertSame(used.getClass(), config.consumerFactory().getClass());
    }
}
