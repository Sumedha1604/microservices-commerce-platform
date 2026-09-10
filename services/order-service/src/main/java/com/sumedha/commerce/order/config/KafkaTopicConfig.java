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
 *
 * <p>{@code order.compensation.v1} <em>is</em> declared here, because order-service publishes it.
 * Its dead-letter topic belongs to its consumer, inventory-service, by the same rule.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    /** Compensation intents order-service publishes. Keyed by orderId, like the payment stream. */
    @Bean
    NewTopic orderCompensationV1Topic() {
        return TopicBuilder.name(KafkaTopics.ORDER_COMPENSATION_V1)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    @Bean
    NewTopic paymentEventsV1DeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS_V1_DLT)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
