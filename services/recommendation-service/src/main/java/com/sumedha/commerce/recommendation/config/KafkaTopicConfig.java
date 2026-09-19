package com.sumedha.commerce.recommendation.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * recommendation-service owns the dead-letter topic for what it consumes. {@code product.events.v1}
 * itself is declared by product-service. Local sizing matches the source topic.
 */
@Configuration
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICATION_FACTOR = 1;

    @Bean
    NewTopic productEventsV1RecommendationDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.PRODUCT_EVENTS_V1_RECOMMENDATION_DLT)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
