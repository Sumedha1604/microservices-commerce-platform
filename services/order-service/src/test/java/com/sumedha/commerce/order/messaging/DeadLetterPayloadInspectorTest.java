package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The inspector's contract is the mirror image of {@link PaymentEventParser}'s: where the parser
 * rejects everything it cannot read, the inspector <strong>never throws</strong> and returns
 * whatever it could recover.
 *
 * <p>That asymmetry is the whole reason this class exists. A record is on the dead-letter topic
 * because something about it was already wrong, so an inspector that threw on bad input would
 * fail on exactly the records an operator most needs to see. The cases below therefore assert two
 * things: that nothing was thrown, and that each field is recovered independently of the others.
 */
class DeadLetterPayloadInspectorTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final DeadLetterPayloadInspector inspector = new DeadLetterPayloadInspector();

    private String authorizedJson() {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PAYMENT_AUTHORIZED, 1,
                Instant.parse("2026-09-08T10:00:00Z"),
                new PaymentAuthorizedEvent(UUID.randomUUID(), ORDER_ID, UUID.randomUUID(),
                        new BigDecimal("59.97"), "USD")));
    }

    // ---------- the well-formed case ----------

    @Test
    void recoversEveryEnvelopeFieldFromAWellFormedPayload() {
        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(authorizedJson());

        assertEquals(EVENT_ID, metadata.eventId());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, metadata.eventType());
        assertEquals(1, metadata.schemaVersion());
        assertEquals(ORDER_ID, metadata.orderId(), "orderId is read from inside the payload block");
    }

    /**
     * The same payload the parser rejects outright. The inspector must still describe it, because
     * "unsupported type, version 9" is precisely the triage information an operator wants.
     */
    @Test
    void recordsAnUnsupportedTypeAndVersionAsObservedRatherThanValidatingThem() {
        String payload = authorizedJson()
                .replace("\"PaymentAuthorized\"", "\"PaymentRefunded\"")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":9");

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertEquals("PaymentRefunded", metadata.eventType());
        assertEquals(9, metadata.schemaVersion());
        assertEquals(EVENT_ID, metadata.eventId());
        assertEquals(ORDER_ID, metadata.orderId());
    }

    // ---------- nothing is unparseable enough to throw ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "{this is not json",
            "{\"eventId\": }",
            "{\"unterminated\": \"string",
            "not json at all",
            "[]",
            "[1,2,3]",
            "\"a bare string\"",
            "42",
            "true",
            "null",
            "{}",
            "{{}}",
            "<xml>not json</xml>",
    })
    void neverThrowsAndYieldsEmptyMetadataForAnythingThatIsNotAnEnvelopeObject(String payload) {
        DeadLetterPayloadInspector.Metadata metadata =
                assertDoesNotThrow(() -> inspector.inspect(payload));

        assertNull(metadata.eventId());
        assertNull(metadata.eventType());
        assertNull(metadata.schemaVersion());
        assertNull(metadata.orderId());
    }

    @Test
    void neverThrowsOnANullPayload() {
        DeadLetterPayloadInspector.Metadata metadata = assertDoesNotThrow(() -> inspector.inspect(null));

        assertNull(metadata.eventId());
        assertNull(metadata.orderId());
    }

    @Test
    void neverThrowsOnWhitespaceOnlyControlCharacters() {
        DeadLetterPayloadInspector.Metadata metadata =
                assertDoesNotThrow(() -> inspector.inspect("\n\t\r "));

        assertNull(metadata.eventId());
        assertNull(metadata.eventType());
    }

    // ---------- fields are recovered independently ----------

    @Test
    void aBrokenPayloadBlockStillYieldsTheTopLevelEnvelopeFields() {
        String payload = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentAuthorized",
                 "schemaVersion":1,"payload":"this should have been an object"}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertEquals(EVENT_ID, metadata.eventId(), "a broken payload block must not cost us the eventId");
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, metadata.eventType());
        assertEquals(1, metadata.schemaVersion());
        assertNull(metadata.orderId());
    }

    @Test
    void aMissingPayloadBlockLeavesOnlyTheOrderIdUnknown() {
        String payload = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentFailed",
                 "schemaVersion":1}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertEquals(EVENT_ID, metadata.eventId());
        assertEquals("PaymentFailed", metadata.eventType());
        assertNull(metadata.orderId());
    }

    @Test
    void anUnrecoverableEventIdDoesNotHideTheRestOfTheEnvelope() {
        String payload = """
                {"eventId":"not-a-uuid","eventType":"PaymentAuthorized","schemaVersion":1,
                 "payload":{"orderId":"33333333-3333-3333-3333-333333333333"}}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertNull(metadata.eventId(), "a non-UUID eventId is reported as unknown, not guessed at");
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, metadata.eventType());
        assertEquals(ORDER_ID, metadata.orderId());
    }

    @Test
    void anUnrecoverableOrderIdDoesNotHideTheEventId() {
        String payload = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentAuthorized",
                 "schemaVersion":1,"payload":{"orderId":"","amount":"59.97"}}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertEquals(EVENT_ID, metadata.eventId());
        assertNull(metadata.orderId());
    }

    /**
     * Wrong JSON types must read as "unknown", never as a coerced value: an inspection row that
     * invented {@code schemaVersion=1} from the string {@code "1"} would be lying to the operator.
     */
    @Test
    void fieldsOfTheWrongJsonTypeAreReportedAsUnknownRatherThanCoerced() {
        String payload = """
                {"eventId":12345,"eventType":99,"schemaVersion":"1",
                 "payload":{"orderId":{"nested":"object"}}}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertNull(metadata.eventId(), "a numeric eventId is not a UUID");
        assertNull(metadata.eventType(), "a numeric eventType is not a type name");
        assertNull(metadata.schemaVersion(), "the string \"1\" must not be coerced to 1");
        assertNull(metadata.orderId());
    }

    @Test
    void explicitJsonNullsAreTreatedAsAbsent() {
        String payload = """
                {"eventId":null,"eventType":null,"schemaVersion":null,"payload":{"orderId":null}}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertNull(metadata.eventId());
        assertNull(metadata.eventType());
        assertNull(metadata.schemaVersion());
        assertNull(metadata.orderId());
    }

    @Test
    void unknownExtraFieldsAreIgnoredInsteadOfRejected() {
        String payload = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentAuthorized",
                 "schemaVersion":1,"somethingNew":{"deep":[1,2,{"deeper":true}]},
                 "payload":{"orderId":"33333333-3333-3333-3333-333333333333","futureField":"x"}}""";

        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(payload);

        assertEquals(EVENT_ID, metadata.eventId());
        assertEquals(ORDER_ID, metadata.orderId());
    }

    /** No Java class name arriving in a payload is ever resolved - the rule the parser follows too. */
    @Test
    void typeMetadataInThePayloadIsNotHonoured() {
        String payload = """
                {"@class":"java.lang.Runtime","__TypeId__":"java.lang.Runtime",
                 "eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentAuthorized",
                 "schemaVersion":1,"payload":{"orderId":"33333333-3333-3333-3333-333333333333"}}""";

        DeadLetterPayloadInspector.Metadata metadata = assertDoesNotThrow(() -> inspector.inspect(payload));

        assertEquals(EVENT_ID, metadata.eventId(), "the envelope is read as plain data, type hints and all");
        assertEquals(ORDER_ID, metadata.orderId());
    }

    @Test
    void aVeryLargePayloadIsInspectedWithoutThrowing() {
        String payload = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentAuthorized",
                 "schemaVersion":1,"junk":"%s",
                 "payload":{"orderId":"33333333-3333-3333-3333-333333333333"}}"""
                .formatted("x".repeat(200_000));

        DeadLetterPayloadInspector.Metadata metadata = assertDoesNotThrow(() -> inspector.inspect(payload));

        assertEquals(EVENT_ID, metadata.eventId());
        assertNotNull(metadata.orderId());
    }
}
