package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Serializes a {@link EventEnvelope} to its explicit JSON wire form and sends it to
 * {@link KafkaTopics#PAYMENT_EVENTS_V1}, keyed by {@code orderId} for per-order ordering.
 *
 * <p>The value is a plain JSON string: no {@code __TypeId__} / class-name headers, so the
 * contract is the {@code common-events} schema alone. Tracing headers (W3C {@code traceparent})
 * are added by {@code KafkaTemplate} observation, not by this class.
 *
 * <p>Send failures are surfaced through the returned future's completion and logged; they are
 * never rethrown into the caller and never retried here beyond the producer's own
 * {@code retries}. Because publication happens after the DB commit, a failure here means the
 * event is lost until the Outbox milestone - it does not undo the committed payment state.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPaymentAuthorized(PaymentAuthorizedEvent payload) {
        publish(EventTypes.PAYMENT_AUTHORIZED, payload.orderId(),
                EventEnvelope.now(EventTypes.PAYMENT_AUTHORIZED, payload));
    }

    public void publishPaymentFailed(PaymentFailedEvent payload) {
        publish(EventTypes.PAYMENT_FAILED, payload.orderId(),
                EventEnvelope.now(EventTypes.PAYMENT_FAILED, payload));
    }

    private void publish(String eventType, UUID orderId, EventEnvelope<?> envelope) {
        String key = orderId.toString();

        String value;
        try {
            value = objectMapper.writeValueAsString(envelope);
        } catch (RuntimeException serializationFailure) {
            log.error("Could not serialize {} event {} (key={}); event NOT published",
                    eventType, envelope.eventId(), key, serializationFailure);
            return;
        }

        CompletableFuture<SendResult<String, String>> sent;
        try {
            sent = kafkaTemplate.send(KafkaTopics.PAYMENT_EVENTS_V1, key, value);
        } catch (RuntimeException sendRejected) {
            log.error("Kafka rejected the {} event {} to {} (key={}) synchronously AFTER the "
                            + "payment transaction committed; the event is lost until the outbox milestone",
                    eventType, envelope.eventId(), KafkaTopics.PAYMENT_EVENTS_V1, key, sendRejected);
            return;
        }

        sent.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish of {} event {} to {} (key={}) failed AFTER the payment "
                                + "transaction committed; the event is lost until the outbox milestone",
                        eventType, envelope.eventId(), KafkaTopics.PAYMENT_EVENTS_V1, key, ex);
            } else if (log.isDebugEnabled()) {
                log.debug("Published {} event {} to {}-{}@{} (key={})", eventType, envelope.eventId(),
                        result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(), key);
            }
        });
    }
}
