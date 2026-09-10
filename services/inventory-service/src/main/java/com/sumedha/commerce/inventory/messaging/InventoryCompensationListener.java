package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.inventory.metrics.InventoryCompensationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Single-record listener for {@code order.compensation.v1}.
 *
 * <p>Deliberately thin: parse, delegate, count. All transactional work lives in
 * {@link InventoryReleaseProcessor}, whose transaction commits before this method returns - and
 * only then does the container commit the offset (ack mode {@code RECORD}).
 *
 * <p>Every exception escapes so the container's {@code DefaultErrorHandler} can decide: bounded
 * retry for transient failures, immediate dead-letter for {@link NonRetryableEventException}.
 * Duplicates are not exceptions at all - the processor reports them as an outcome - so nothing
 * here has to guess whether a failure was "really" a duplicate.
 */
@Component
public class InventoryCompensationListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCompensationListener.class);

    private final InventoryCompensationEventParser parser;
    private final InventoryReleaseProcessor processor;
    private final InventoryCompensationMetrics metrics;

    public InventoryCompensationListener(InventoryCompensationEventParser parser,
                                         InventoryReleaseProcessor processor,
                                         InventoryCompensationMetrics metrics) {
        this.parser = parser;
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_COMPENSATION_V1,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onCompensationEvent(
            @Payload String value,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

        metrics.received();
        InventoryReleaseProcessor.Outcome outcome;
        try {
            outcome = processor.process(parser.parse(value));
        } catch (RuntimeException failure) {
            metrics.failed();
            throw failure;
        }

        if (outcome == InventoryReleaseProcessor.Outcome.RELEASED) {
            metrics.released();
        } else {
            metrics.duplicateIgnored();
        }
        log.debug("Processed compensation record (key={}): {}", key, outcome);
    }
}
