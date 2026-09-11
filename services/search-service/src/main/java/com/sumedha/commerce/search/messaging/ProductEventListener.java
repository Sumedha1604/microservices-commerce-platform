package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.search.metrics.SearchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Single-record listener for {@code product.events.v1}, in consumer group {@code search-service}.
 *
 * <p>Deliberately thin: parse, delegate, count. All transactional work lives in
 * {@link ProductProjectionProcessor}, whose transaction commits before this method returns - and only
 * then does the container commit the offset (ack mode {@code RECORD}). Every exception escapes so the
 * container's error handler decides between bounded retry and immediate dead-lettering.
 */
@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductEventParser parser;
    private final ProductProjectionProcessor processor;
    private final SearchMetrics metrics;

    public ProductEventListener(ProductEventParser parser, ProductProjectionProcessor processor,
                                SearchMetrics metrics) {
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
            metrics.indexingFailed();
            // The key is the productId; the message names the eventId when one could be read. The
            // payload itself is never logged.
            log.warn("Product event not applied key={} failure={}", key, failure.toString());
            throw failure;
        }

        switch (outcome) {
            case UPSERTED -> metrics.projectionUpserted();
            case DELETED -> metrics.projectionDeleted();
            case STALE_IGNORED -> metrics.projectionStaleIgnored();
            case DUPLICATE -> metrics.duplicateIgnored();
        }
    }
}
