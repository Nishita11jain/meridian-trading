package com.trading.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}")
            String bootstrapServers) {

        Map<String, Object> properties =
                reliableProducerProperties(bootstrapServers);

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                contractJsonSerializer()
        );
    }

    /**
     * JSON as contracts/event-schemas/kafka-topics.md fixes it: dates are RFC 3339 strings
     * such as 2026-09-28T09:14:22Z, not the numeric epoch seconds Jackson
     * writes by default, and no Spring type headers are added.
     */
    private static JsonSerializer<Object> contractJsonSerializer() {
        ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JsonSerializer<Object> serializer = new JsonSerializer<>(mapper);
        serializer.setAddTypeInfo(false);
        return serializer;
    }

    private static Map<String, Object> reliableProducerProperties(String bootstrapServers) {

        Map<String, Object> properties =
                new HashMap<>();

        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        properties.put(
                ProducerConfig.ACKS_CONFIG,
                "all"
        );

        properties.put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true
        );

        // High retry count; delivery.timeout.ms (default two minutes) is what actually bounds a send.
        properties.put(
                ProducerConfig.RETRIES_CONFIG,
                Integer.MAX_VALUE
        );

        properties.put(
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
                5
        );

        // Do not let send() hang for a minute on an unreachable broker; callers wait on the result themselves.
        properties.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                5000
        );

        return properties;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> producerFactory) {

        return new KafkaTemplate<>(
                producerFactory
        );
    }

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}")
            String bootstrapServers) {

        return new DefaultKafkaProducerFactory<>(
                reliableProducerProperties(bootstrapServers),
                new StringSerializer(),
                new StringSerializer()
        );
    }

    @Bean
    public KafkaTemplate<String, String> kafkaStringTemplate(
            ProducerFactory<String, String> stringProducerFactory) {

        return new KafkaTemplate<>(stringProducerFactory);
    }
}
