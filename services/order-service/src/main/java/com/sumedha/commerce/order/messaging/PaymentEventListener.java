package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Single-record listener for {@code payment.events.v1}.
 *
 * <p>Deliberately thin: parse, delegate, log. All transactional work lives in
 * {@link PaymentEventProcessor}, whose transaction commits before this method returns - and only
 * then does the container commit the offset (ack mode {@code RECORD}).
 *
 * <p>Exceptions are allowed to escape so the container's {@code DefaultErrorHandler} can decide:
 * bounded retry for transient failures, immediate dead-letter for
 * {@link NonRetryableEventException}.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentEventParser parser;
    private final PaymentEventProcessor processor;

    public PaymentEventListener(PaymentEventParser parser, PaymentEventProcessor processor) {
        this.parser = parser;
        this.processor = processor;
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_EVENTS_V1,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onPaymentEvent(
            @Payload String value,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

        PaymentEvent event = parser.parse(value);
        PaymentEventProcessor.Outcome outcome = processor.process(event);

        log.debug("Processed {} {} for order {} (key={}): {}",
                event.eventType(), event.eventId(), event.orderId(), key, outcome);
    }
}
