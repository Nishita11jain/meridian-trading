package com.trading.api.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer side of the Trade REST API. Until Sprint 10 this service only produced events; the
 * extension modules add consumers (notifications on trade-events, watchlists on market-data), so the
 * listener infrastructure is enabled here.
 *
 * <p>Messages are read as plain JSON strings and parsed by each consumer, the same way the executor
 * reads them, because the producer writes JSON without Spring type headers. Offsets are committed
 * manually (AckMode.MANUAL) so a consumer acknowledges only after it has durably recorded its work,
 * per contracts/kafka-topics.md.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> extensionsConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Each @KafkaListener sets its own group id, so none is fixed on the factory.
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> extensionsKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(extensionsConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(extensionsErrorHandler());
        return factory;
    }

    /**
     * A message that still fails after the retries goes to "<topic>.DLT" instead of being dropped: the
     * offset moves on, but the payload is kept where someone can read it. The DLT topics are created by
     * infrastructure/kafka/scripts/create-topics.sh. Values are plain JSON strings, so this template
     * writes strings rather than going through the order publisher's JSON serializer.
     */
    @Bean
    public DefaultErrorHandler extensionsErrorHandler() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> deadLetterTemplate =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, String>(props));
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(deadLetterTemplate, KafkaConsumerConfig::deadLetterDestination);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3)); // 1 second apart, 3 retries
    }

    /** Same topic with ".DLT" added; no fixed partition, so the DLT does not need as many as the source. */
    static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record, Exception failure) {
        return new TopicPartition(record.topic() + ".DLT", -1);
    }
}
