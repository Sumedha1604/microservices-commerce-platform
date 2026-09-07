package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope validation and explicit payload binding. Everything unreadable must be rejected as
 * non-retryable rather than silently accepted or endlessly retried.
 */
class PaymentEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-07T12:34:56.789Z");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PaymentEventParser parser = new PaymentEventParser();

    private String authorizedJson() {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED, 1, OCCURRED_AT,
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("59.97"), "USD")));
    }

    private String failedJson() {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_FAILED, 1, OCCURRED_AT,
                new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, "card declined")));
    }

    // ---- happy paths ----

    @Test
    void parsesPaymentAuthorizedIntoAnExplicitlyTypedPayload() {
        PaymentEvent parsed = parser.parse(authorizedJson());

        PaymentEvent.Authorized authorized = assertInstanceOf(PaymentEvent.Authorized.class, parsed);
        assertEquals(EVENT_ID, authorized.eventId());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, authorized.eventType());
        assertEquals(ORDER_ID, authorized.orderId());
        assertEquals(PAYMENT_ID, authorized.payload().paymentId());
        assertEquals(USER_ID, authorized.payload().userId());
        assertEquals(0, new BigDecimal("59.97").compareTo(authorized.payload().amount()));
        assertEquals("USD", authorized.payload().currency());
    }

    @Test
    void parsesPaymentFailedIntoAnExplicitlyTypedPayload() {
        PaymentEvent parsed = parser.parse(failedJson());

        PaymentEvent.Failed failed = assertInstanceOf(PaymentEvent.Failed.class, parsed);
        assertEquals(EVENT_ID, failed.eventId());
        assertEquals(EventTypes.PAYMENT_FAILED, failed.eventType());
        assertEquals(ORDER_ID, failed.orderId());
        assertEquals("card declined", failed.payload().failureReason());
    }

    @Test
    void parsesTheProducersExactWireFormatWithoutAnyTypeMetadata() {
        // byte-for-byte shape observed on the real broker from payment-service
        String wire = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-07T12:34:56.789Z\","
                + "\"payload\":{\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"amount\":10.00,\"currency\":\"USD\"}}";

        PaymentEvent.Authorized parsed = assertInstanceOf(PaymentEvent.Authorized.class, parser.parse(wire));

        assertEquals(ORDER_ID, parsed.orderId());
        assertEquals(0, new BigDecimal("10.00").compareTo(parsed.payload().amount()));
    }

    // ---- rejections, all non-retryable ----

    @Test
    void rejectsMalformedJson() {
        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> parser.parse("{not json at all"));
        assertTrue(thrown.getMessage().contains("valid v1 event envelope"));
    }

    @Test
    void rejectsNullAndBlankValues() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(null));
        assertThrows(NonRetryableEventException.class, () -> parser.parse("   "));
    }

    @Test
    void rejectsUnknownEventType() {
        String wire = authorizedJson().replace("\"PaymentAuthorized\"", "\"PaymentRefunded\"");

        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(wire));
        assertTrue(thrown.getMessage().contains("Unsupported eventType 'PaymentRefunded'"));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String wire = authorizedJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(wire));
        assertTrue(thrown.getMessage().contains("Unsupported schemaVersion 2"));
    }

    @Test
    void rejectsMissingEnvelopeFields() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse("{}"));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(
                "{\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                        + "\"occurredAt\":\"2026-09-07T12:34:56.789Z\",\"payload\":{}}"));
    }

    @Test
    void rejectsMissingPayload() {
        String wire = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-07T12:34:56.789Z\"}";

        assertThrows(NonRetryableEventException.class, () -> parser.parse(wire));
    }

    @Test
    void rejectsPayloadWithoutOrderId() {
        String wire = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-07T12:34:56.789Z\","
                + "\"payload\":{\"paymentId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"userId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"amount\":10.00,\"currency\":\"USD\"}}";

        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(wire));
        assertTrue(thrown.getMessage().contains("no orderId"));
    }

    @Test
    void rejectsUnparseableOccurredAt() {
        String wire = authorizedJson().replace("\"2026-09-07T12:34:56.789Z\"", "\"not-a-timestamp\"");

        assertThrows(NonRetryableEventException.class, () -> parser.parse(wire));
    }

    @Test
    void toleratesUnknownAdditiveFields() {
        String wire = authorizedJson().replace("\"payload\":", "\"traceId\":\"future-field\",\"payload\":");

        assertInstanceOf(PaymentEvent.Authorized.class, parser.parse(wire));
    }
}
