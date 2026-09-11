package com.sumedha.commerce.product.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Focused Kafka boundary for an already-serialized durable outbox record.
 */
@Component
public class ProductEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public ProductEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, String>> publish(String topic, String key, String payload) {
        return kafkaTemplate.send(topic, key, payload);
    }
}
