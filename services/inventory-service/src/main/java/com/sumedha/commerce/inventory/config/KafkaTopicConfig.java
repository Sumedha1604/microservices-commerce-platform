package com.sumedha.commerce.inventory.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * inventory-service owns the dead-letter topic for the stream it consumes.
 *
 * <p>{@code order.compensation.v1} itself is declared by order-service (its publisher); it is not
 * redeclared here. This mirrors how order-service declares the payment dead-letter topic without
 * redeclaring the payment stream. Local development sizing matches the source topic: 3
 * partitions, replication factor 1.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic orderCompensationV1DeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_COMPENSATION_V1_DLT)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
