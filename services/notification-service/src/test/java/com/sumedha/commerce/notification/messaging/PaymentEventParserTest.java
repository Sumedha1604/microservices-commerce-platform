package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope and payload validation for payment events. Anything the notification consumer cannot
 * read exactly must be rejected as non-retryable - never guessed at, never retried.
 */
class PaymentEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-11T10:15:30Z");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PaymentEventParser parser = new PaymentEventParser();

    private String authorized(BigDecimal amount, String currency) {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, amount, currency)));
    }

    private String authorized() {
        return authorized(new BigDecimal("59.97"), "USD");
    }

    private String failed(String reason) {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_FAILED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, reason)));
    }

    // ---- valid events ----

    @Test
    void parsesAValidPaymentAuthorized() {
        PaymentEvent event = parser.parse(authorized());

        PaymentEvent.Authorized authorized = assertInstanceOf(PaymentEvent.Authorized.class, event);
        assertEquals(EVENT_ID, authorized.eventId());
        assertEquals("PaymentAuthorized", authorized.eventType());
        assertEquals(OCCURRED_AT, authorized.occurredAt());
        assertEquals(PAYMENT_ID, authorized.paymentId());
        assertEquals(ORDER_ID, authorized.orderId());
        assertEquals(USER_ID, authorized.userId());
        assertEquals("USD", authorized.payload().currency());
    }

    /** Money must survive parsing exactly - no double round trip, no lost scale. */
    @Test
    void theAmountIsBoundExactlyIncludingScale() {
        PaymentEvent.Authorized cents = (PaymentEvent.Authorized) parser.parse(authorized(new BigDecimal("59.97"), "USD"));
        PaymentEvent.Authorized whole = (PaymentEvent.Authorized) parser.parse(authorized(new BigDecimal("50.00"), "USD"));

        assertEquals("59.97", cents.payload().amount().toPlainString());
        assertEquals("50.00", whole.payload().amount().toPlainString());
    }

    @Test
    void parsesAValidPaymentFailed() {
        PaymentEvent event = parser.parse(failed("card declined"));

        PaymentEvent.Failed failed = assertInstanceOf(PaymentEvent.Failed.class, event);
        assertEquals(EVENT_ID, failed.eventId());
        assertEquals("PaymentFailed", failed.eventType());
        assertEquals(OCCURRED_AT, failed.occurredAt());
        assertEquals(PAYMENT_ID, failed.paymentId());
        assertEquals(ORDER_ID, failed.orderId());
        assertEquals(USER_ID, failed.userId());
        assertEquals("card declined", failed.payload().failureReason());
    }

    @Test
    void aPaymentFailedWithoutAReasonIsStillValid() {
        PaymentEvent.Failed failed = assertInstanceOf(PaymentEvent.Failed.class, parser.parse(failed(null)));

        assertNull(failed.payload().failureReason());
    }

    // ---- unreadable records ----

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "{this is not json",
            "not json at all",
            "[]",
            "\"a bare string\"",
            "42",
            "{}",
            "{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}",
            "{\"eventId\":\"not-a-uuid\",\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                    + "\"occurredAt\":\"2026-09-11T10:15:30Z\",\"payload\":{}}",
    })
    void anythingThatIsNotAV1EnvelopeIsNonRetryable(String value) {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(value));
    }

    @Test
    void aNullValueIsNonRetryable() {
        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(null));

        assertTrue(rejected.getMessage().contains("empty"), rejected.getMessage());
    }

    @Test
    void aMissingOccurredAtIsRejected() {
        String noOccurredAt = authorized().replace("\"occurredAt\":\"" + OCCURRED_AT + "\"", "\"occurredAt\":null");

        assertThrows(NonRetryableEventException.class, () -> parser.parse(noOccurredAt));
    }

    @Test
    void aMissingEventTypeIsRejected() {
        String noType = authorized().replace("\"eventType\":\"PaymentAuthorized\"", "\"eventType\":null");

        assertThrows(NonRetryableEventException.class, () -> parser.parse(noType));
    }

    @Test
    void aPayloadThatIsNotAnObjectIsRejected() {
        String value = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PaymentAuthorized\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-09-11T10:15:30Z\",\"payload\":\"not an object\"}";

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(value));

        assertTrue(rejected.getMessage().contains("does not match"), rejected.getMessage());
    }

    // ---- version and type ----

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 9, -1})
    void anUnsupportedSchemaVersionIsRejectedAndSaysSo(int version) {
        String other = authorized().replace("\"schemaVersion\":1", "\"schemaVersion\":" + version);

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(other));

        assertTrue(rejected.getMessage().contains("Unsupported schemaVersion " + version), rejected.getMessage());
    }

    @Test
    void aMissingSchemaVersionIsRejected() {
        String missing = authorized().replace("\"schemaVersion\":1,", "");

        assertThrows(NonRetryableEventException.class, () -> parser.parse(missing));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PaymentCaptured", "InventoryReleaseRequested", "paymentauthorized", ""})
    void anEventTypeThisConsumerDoesNotHandleIsRejected(String eventType) {
        String other = authorized().replace("\"PaymentAuthorized\"", "\"" + eventType + "\"");

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(other));

        assertTrue(rejected.getMessage().contains("Unsupported eventType"), rejected.getMessage());
    }

    // ---- required identifiers ----

    @ParameterizedTest
    @ValueSource(strings = {"paymentId", "orderId", "userId"})
    void aPaymentAuthorizedMissingARequiredIdIsRejected(String field) {
        String missing = withoutId(authorized(), field);

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(missing));

        assertTrue(rejected.getMessage().contains("has no " + field), rejected.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentId", "orderId", "userId"})
    void aPaymentFailedMissingARequiredIdIsRejected(String field) {
        String missing = withoutId(failed("card declined"), field);

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(missing));

        assertTrue(rejected.getMessage().contains("has no " + field), rejected.getMessage());
    }

    @Test
    void aMalformedIdInThePayloadIsRejected() {
        String malformed = authorized().replace("\"orderId\":\"" + ORDER_ID + "\"", "\"orderId\":\"not-a-uuid\"");

        assertThrows(NonRetryableEventException.class, () -> parser.parse(malformed));
    }

    @Test
    void aPaymentAuthorizedWithoutAnAmountIsRejected() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(authorized(null, "USD")));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(authorized(new BigDecimal("-0.01"), "USD")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "USDX", "12$", ""})
    void aPaymentAuthorizedWithoutAValidCurrencyIsRejected(String currency) {
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(authorized(new BigDecimal("10.00"), currency)));
    }

    // ---- no type metadata ----

    /** No Java class name arriving over Kafka is ever resolved to a type. */
    @Test
    void typeMetadataInTheRecordIsIgnoredRatherThanHonoured() {
        String withTypeHints = authorized()
                .replace("{\"eventId\"", "{\"@class\":\"java.lang.Runtime\",\"__TypeId__\":\"java.lang.ProcessBuilder\",\"eventId\"")
                .replace("{\"paymentId\"", "{\"@type\":\"java.net.URL\",\"paymentId\"");

        PaymentEvent event = parser.parse(withTypeHints);

        assertInstanceOf(PaymentEvent.Authorized.class, event, "the eventType string alone chooses the payload type");
        assertEquals(EVENT_ID, event.eventId());
        assertEquals(ORDER_ID, event.orderId());
    }

    private static String withoutId(String value, String field) {
        UUID id = switch (field) {
            case "paymentId" -> PAYMENT_ID;
            case "orderId" -> ORDER_ID;
            default -> USER_ID;
        };
        String replaced = value.replace("\"" + field + "\":\"" + id + "\"", "\"" + field + "\":null");
        assertTrue(!replaced.equals(value), "fixture must actually remove " + field);
        return replaced;
    }
}
