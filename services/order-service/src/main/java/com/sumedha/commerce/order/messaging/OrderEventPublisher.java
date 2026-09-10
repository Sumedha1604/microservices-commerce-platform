package com.sumedha.commerce.order.messaging;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Focused Kafka boundary for an already-serialized durable outbox record.
 *
 * <p>It takes a topic, key and payload string and nothing else: the publisher must never be able
 * to reinterpret what the business transaction promised.
 *
 * <p>Uses the observation-enabled template so the send produces a producer span and a W3C
 * {@code traceparent} for the consumer to continue. Note that this trace begins at the publish,
 * not at the payment event that queued the row - see the tracing boundary in
 * {@code docs/events/inventory-compensation.md}.
 */
@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderEventPublisher(@Qualifier("compensationKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, String>> publish(String topic, String key, String payload) {
        return kafkaTemplate.send(topic, key, payload);
    }
}
