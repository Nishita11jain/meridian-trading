package com.trading.api.config;

import com.trading.domain.DuplicateOrderChecker;
import com.trading.domain.OrderPlacementService;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.service.DatabaseDuplicateOrderChecker;
import com.trading.api.service.KafkaOrderEventPublisher;
import com.trading.api.service.OrderEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainBeansConfig {

    @Bean
    public DuplicateOrderChecker duplicateOrderChecker(OrderMapper orderMapper) {
        return new DatabaseDuplicateOrderChecker(orderMapper);
    }

    @Bean
    public OrderPlacementService orderPlacementService(DuplicateOrderChecker duplicateOrderChecker) {
        return new OrderPlacementService(duplicateOrderChecker);
    }

    @Bean
    public OrderEventPublisher orderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaOrderEventPublisher(kafkaTemplate);
    }
}
