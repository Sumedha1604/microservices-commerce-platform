package com.sumedha.commerce.common.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trips the generic {@link EventEnvelope} with the classic Jackson 2 databind stack
 * (one of the two Spring Boot 4 ships). The concrete payload type is supplied at the call
 * site via {@link TypeReference} / parametric type - no default typing, no class-name headers.
 */
class EventEnvelopeTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-06T12:34:56.789Z");

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EventEnvelope<PaymentAuthorizedEvent> sampleEnvelope() {
        PaymentAuthorizedEvent payload =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("59.97"), "USD");
        return new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT, payload);
    }

    @Test
    void serializesToFlatJsonWithFixedKeysAndNoTypeMetadata() throws Exception {
        String json = mapper.writeValueAsString(sampleEnvelope());

        assertEquals(
                "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"eventType\":\"PaymentAuthorized\","
                        + "\"schemaVersion\":1,"
                        + "\"occurredAt\":\"2026-09-06T12:34:56.789Z\","
                        + "\"payload\":{"
                        + "\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                        + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                        + "\"amount\":59.97,"
                        + "\"currency\":\"USD\"}}",
                json);
    }

    @Test
    void roundTripsThroughGenericTypeReferencePreservingEveryField() throws Exception {
        String json = mapper.writeValueAsString(sampleEnvelope());

        EventEnvelope<PaymentAuthorizedEvent> back =
                mapper.readValue(json, new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});

        assertEquals(EVENT_ID, back.eventId());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, back.eventType());
        assertEquals(1, back.schemaVersion());
        assertEquals(OCCURRED_AT, back.occurredAt());
        assertNotNull(back.payload());
        assertEquals(PAYMENT_ID, back.payload().paymentId());
        assertEquals(ORDER_ID, back.payload().orderId());
        assertEquals(USER_ID, back.payload().userId());
        assertEquals(0, new BigDecimal("59.97").compareTo(back.payload().amount()));
        assertEquals("USD", back.payload().currency());
        assertEquals(sampleEnvelope(), back);
    }

    @Test
    void deserializesFromHandWrittenCanonicalWireJson() throws Exception {
        String wire = "{"
                + "\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentAuthorized\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-06T12:34:56.789Z\","
                + "\"payload\":{"
                + "\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"amount\":59.97,"
                + "\"currency\":\"USD\"}}";

        EventEnvelope<PaymentAuthorizedEvent> back = mapper.readValue(
                wire,
                mapper.getTypeFactory().constructParametricType(EventEnvelope.class, PaymentAuthorizedEvent.class));

        assertEquals(sampleEnvelope(), back);
    }

    @Test
    void toleratesUnknownAdditiveFields() throws Exception {
        String wireWithExtraField = "{"
                + "\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentAuthorized\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-06T12:34:56.789Z\","
                + "\"traceId\":\"future-additive-field\","
                + "\"payload\":{"
                + "\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"amount\":59.97,\"currency\":\"USD\",\"provider\":\"acme\"}}";

        EventEnvelope<PaymentAuthorizedEvent> back = mapper.readValue(
                wireWithExtraField,
                mapper.getTypeFactory().constructParametricType(EventEnvelope.class, PaymentAuthorizedEvent.class));

        assertEquals(sampleEnvelope(), back);
    }

    @Test
    void nowFactorySetsV1SchemaAndFreshIdentity() {
        PaymentAuthorizedEvent payload =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("10.00"), "USD");

        EventEnvelope<PaymentAuthorizedEvent> a = EventEnvelope.now(EventTypes.PAYMENT_AUTHORIZED, payload);
        EventEnvelope<PaymentAuthorizedEvent> b = EventEnvelope.now(EventTypes.PAYMENT_AUTHORIZED, payload);

        assertEquals(1, a.schemaVersion());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, a.eventType());
        assertNotNull(a.eventId());
        assertNotNull(a.occurredAt());
        assertEquals(payload, a.payload());
        assertNotNull(b.eventId());
        assertEquals(a.payload(), b.payload());
        assertNotEquals(a.eventId(), b.eventId());
    }

    @Test
    void rejectsNullRequiredFields() {
        PaymentAuthorizedEvent payload =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("1.00"), "USD");

        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>(null, EventTypes.PAYMENT_AUTHORIZED, 1, OCCURRED_AT, payload));
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>(EVENT_ID, null, 1, OCCURRED_AT, payload));
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED, 1, null, payload));
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<PaymentAuthorizedEvent>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED, 1, OCCURRED_AT, null));
    }
}
