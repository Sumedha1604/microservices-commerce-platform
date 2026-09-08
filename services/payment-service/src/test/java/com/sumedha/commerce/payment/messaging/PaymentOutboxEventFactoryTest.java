package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentOutboxEventFactoryTest {

    private final PaymentOutboxEventFactory factory = new PaymentOutboxEventFactory();
    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void createsAuthorizedRowContainingTheExactV1WireEnvelope() {
        UUID orderId = UUID.randomUUID();
        Payment payment = new Payment(orderId, UUID.randomUUID(), new BigDecimal("59.97"), "USD");
        payment.authorize("stripe", "ref-1");

        var row = factory.paymentAuthorized(payment);
        EventEnvelope<PaymentAuthorizedEvent> envelope = json.readValue(row.getPayload(),
                new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});

        assertEquals(row.getEventId(), envelope.eventId());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, row.getEventType());
        assertEquals(EventEnvelope.SCHEMA_VERSION_V1, row.getSchemaVersion());
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, row.getTopic());
        assertEquals(orderId.toString(), row.getEventKey());
        assertEquals(OutboxEventStatus.PENDING, row.getStatus());
        assertNotNull(envelope.occurredAt());
        assertEquals(payment.getId(), envelope.payload().paymentId());
        assertFalse(row.getPayload().contains("@class"));
    }

    @Test
    void createsFailedRowFromTheSanitizedPersistedReason() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "USD");
        payment.fail("  card declined  ");

        var row = factory.paymentFailed(payment);
        EventEnvelope<PaymentFailedEvent> envelope = json.readValue(row.getPayload(),
                new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});

        assertEquals(EventTypes.PAYMENT_FAILED, envelope.eventType());
        assertEquals("card declined", envelope.payload().failureReason());
    }
}
