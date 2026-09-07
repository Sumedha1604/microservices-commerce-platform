package com.sumedha.commerce.common.events;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Same generic-envelope round-trip, but against Jackson 3 (tools.jackson) - the databind
 * Spring Boot 4's auto-configured {@code ObjectMapper} actually uses. Proves the contract
 * binds under both stacks with a plain, out-of-the-box mapper, the concrete payload type
 * supplied via {@link TypeReference} - no default typing, no class-name headers.
 */
class EventEnvelopeJackson3Test {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-06T12:34:56.789Z");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void authorizedEnvelopeRoundTripsViaTypeReference() {
        EventEnvelope<PaymentAuthorizedEvent> envelope = new EventEnvelope<>(
                EVENT_ID, EventTypes.PAYMENT_AUTHORIZED, EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("59.97"), "USD"));

        String json = mapper.writeValueAsString(envelope);
        EventEnvelope<PaymentAuthorizedEvent> back =
                mapper.readValue(json, new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});

        assertEquals(envelope, back);
        assertEquals(OCCURRED_AT, back.occurredAt());
        assertEquals(1, back.schemaVersion());
        assertEquals(0, new BigDecimal("59.97").compareTo(back.payload().amount()));
    }

    @Test
    void failedEnvelopeRoundTripsViaTypeReference() {
        EventEnvelope<PaymentFailedEvent> envelope = new EventEnvelope<>(
                EVENT_ID, EventTypes.PAYMENT_FAILED, EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, "insufficient funds"));

        String json = mapper.writeValueAsString(envelope);
        EventEnvelope<PaymentFailedEvent> back =
                mapper.readValue(json, new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});

        assertEquals(envelope, back);
        assertEquals("insufficient funds", back.payload().failureReason());
    }

    @Test
    void deserializesFromHandWrittenCanonicalWireJson() {
        String wire = "{"
                + "\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentFailed\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-06T12:34:56.789Z\","
                + "\"payload\":{"
                + "\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"failureReason\":\"card declined\"}}";

        EventEnvelope<PaymentFailedEvent> back =
                mapper.readValue(wire, new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});

        assertEquals(EVENT_ID, back.eventId());
        assertEquals(EventTypes.PAYMENT_FAILED, back.eventType());
        assertEquals(OCCURRED_AT, back.occurredAt());
        assertEquals(new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, "card declined"), back.payload());
    }
}
