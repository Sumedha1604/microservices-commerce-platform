package com.sumedha.commerce.search.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * search-service owns the dead-letter topic for what it consumes. {@code product.events.v1} itself is
 * declared by product-service. Local sizing matches the source topic: 3 partitions, replication factor 1.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic productEventsV1SearchDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
