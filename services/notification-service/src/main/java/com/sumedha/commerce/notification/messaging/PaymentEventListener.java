package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.notification.metrics.NotificationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Single-record listener for {@code payment.events.v1}, in consumer group
 * {@code notification-service}.
 *
 * <p>Deliberately thin: parse, delegate, count. All transactional work lives in
 * {@link NotificationEventProcessor}, whose transaction commits before this method returns - and
 * only then does the container commit the offset (ack mode {@code RECORD}).
 *
 * <p>Every exception escapes so the container's {@code DefaultErrorHandler} can decide: bounded
 * retry for transient failures, immediate dead-letter for {@link NonRetryableEventException}.
 * Duplicates are not exceptions - the processor reports them as an outcome.
 *
 * <p>This listener never calls another service. Everything a notification says comes from the
 * event itself.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentEventParser parser;
    private final NotificationEventProcessor processor;
    private final NotificationMetrics metrics;

    public PaymentEventListener(PaymentEventParser parser,
                                NotificationEventProcessor processor,
                                NotificationMetrics metrics) {
        this.parser = parser;
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_EVENTS_V1,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(
            @Payload String value,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

        metrics.received();
        NotificationEventProcessor.Result result;
        try {
            result = processor.process(parser.parse(value));
        } catch (RuntimeException failure) {
            metrics.failed();
            // The key is the orderId; the message names the eventId when one could be read. The
            // payload itself is never logged.
            log.warn("Payment event not processed key={} failure={}", key, failure.toString());
            throw failure;
        }

        if (result.outcome() == NotificationEventProcessor.Outcome.CREATED) {
            metrics.created(result.notificationType());
        } else {
            metrics.duplicateIgnored();
        }
    }
}
