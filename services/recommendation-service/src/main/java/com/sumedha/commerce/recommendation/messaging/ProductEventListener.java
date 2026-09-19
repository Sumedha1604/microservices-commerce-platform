package com.sumedha.commerce.recommendation.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.recommendation.metrics.RecommendationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Single-record listener for {@code product.events.v1}, in consumer group
 * {@code recommendation-service}. Parse, delegate, count; every exception escapes to the container's
 * error handler (bounded retry, or immediate dead-letter for {@link NonRetryableEventException}).
 */
@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductEventParser parser;
    private final ProductProjectionProcessor processor;
    private final RecommendationMetrics metrics;

    public ProductEventListener(ProductEventParser parser, ProductProjectionProcessor processor,
                                RecommendationMetrics metrics) {
        this.parser = parser;
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopics.PRODUCT_EVENTS_V1,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onProductEvent(
            @Payload String value,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

        metrics.eventReceived();
        ProductProjectionProcessor.Outcome outcome;
        try {
            outcome = processor.process(parser.parse(value));
        } catch (RuntimeException failure) {
            metrics.failed();
            // The key is the productId; the payload itself is never logged.
            log.warn("Product event not applied key={} failure={}", key, failure.toString());
            throw failure;
        }

        switch (outcome) {
            case UPSERTED -> metrics.projectionUpserted();
            case DELETED -> metrics.projectionDeleted();
            case STALE_IGNORED -> metrics.staleIgnored();
            case DUPLICATE -> metrics.duplicateIgnored();
        }
    }
}
