package com.sumedha.commerce.order.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * order-service owns the dead-letter topic for the stream it consumes.
 *
 * <p>{@code payment.events.v1} itself is declared by payment-service (its publisher); it is not
 * redeclared here. Local development sizing matches the source topic: 3 partitions, replication
 * factor 1.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic paymentEventsV1DeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS_V1_DLT)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
