package com.sumedha.commerce.product.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * product-service declares the stream it publishes; the broker never relies on auto-creation.
 *
 * <p>Local development sizing, matching the other event streams: 3 partitions (per-product
 * ordering via the {@code productId} key), replication factor 1.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic productEventsV1Topic() {
        return TopicBuilder.name(KafkaTopics.PRODUCT_EVENTS_V1)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
