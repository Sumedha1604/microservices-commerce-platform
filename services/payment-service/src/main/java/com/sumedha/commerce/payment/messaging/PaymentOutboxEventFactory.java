package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.entity.PaymentOutboxEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class PaymentOutboxEventFactory {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public PaymentOutboxEvent paymentAuthorized(Payment payment) {
        return create(EventTypes.PAYMENT_AUTHORIZED, payment,
                new PaymentAuthorizedEvent(payment.getId(), payment.getOrderId(), payment.getUserId(),
                        payment.getAmount(), payment.getCurrency()));
    }

    public PaymentOutboxEvent paymentFailed(Payment payment) {
        return create(EventTypes.PAYMENT_FAILED, payment,
                new PaymentFailedEvent(payment.getId(), payment.getOrderId(), payment.getUserId(),
                        payment.getFailureReason()));
    }

    private PaymentOutboxEvent create(String eventType, Payment payment, Object eventPayload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        EventEnvelope<Object> envelope = new EventEnvelope<>(eventId, eventType,
                EventEnvelope.SCHEMA_VERSION_V1, occurredAt, eventPayload);
        String serialized = objectMapper.writeValueAsString(envelope);
        return new PaymentOutboxEvent(payment.getId(), eventId, eventType,
                EventEnvelope.SCHEMA_VERSION_V1, KafkaTopics.PAYMENT_EVENTS_V1,
                payment.getOrderId().toString(), serialized, occurredAt);
    }
}
