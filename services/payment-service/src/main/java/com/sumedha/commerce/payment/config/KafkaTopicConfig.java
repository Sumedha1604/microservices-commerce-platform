package com.sumedha.commerce.payment.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the payment event topic so the broker never relies on auto-creation.
 * {@code KafkaAdmin} (auto-configured once {@code spring.kafka.bootstrap-servers} is set)
 * applies these {@link NewTopic} beans on startup.
 *
 * <p>Local development sizing: 3 partitions (per-order ordering via the {@code orderId} key,
 * some consumer parallelism), replication factor 1 (single-node broker). Production would
 * raise both.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic paymentEventsV1Topic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS_V1)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
