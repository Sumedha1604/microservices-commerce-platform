package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope validation for the compensation stream. Everything unreadable must be rejected as
 * non-retryable rather than silently accepted or endlessly retried.
 *
 * <p>The bar here is higher than for an ordinary consumer: acting on a payload we do not fully
 * understand would move real stock. So the parser refuses anything it cannot bind exactly, and
 * every refusal is terminal - a record that cannot be read now will not read better on the third
 * attempt, and retrying it only delays the compensation queued behind it.
 */
class InventoryCompensationEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final InventoryCompensationEventParser parser = new InventoryCompensationEventParser();

    private String eventWith(List<InventoryReleaseLine> lines) {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID,
                EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1,
                Instant.parse("2026-09-10T12:00:00Z"),
                new InventoryReleaseRequestedEvent(ORDER_ID, "Payment failed: card declined", lines)));
    }

    private String validEvent() {
        return eventWith(List.of(new InventoryReleaseLine(PRODUCT_ID, 3)));
    }

    // ---- happy path ----

    @Test
    void parsesAWellFormedReleaseRequest() {
        InventoryReleaseCommand command = parser.parse(validEvent());

        assertEquals(EVENT_ID, command.eventId());
        assertEquals("InventoryReleaseRequested", command.eventType());
        assertEquals(ORDER_ID, command.orderId());
        assertEquals(1, command.payload().lines().size());
        assertEquals(PRODUCT_ID, command.payload().lines().getFirst().productId());
        assertEquals(3, command.payload().lines().getFirst().quantity());
        assertTrue(command.payload().reason().contains("card declined"));
    }

    @Test
    void parsesAMultiLineReleaseRequest() {
        UUID second = UUID.randomUUID();

        InventoryReleaseCommand command = parser.parse(eventWith(List.of(
                new InventoryReleaseLine(PRODUCT_ID, 3), new InventoryReleaseLine(second, 1))));

        assertEquals(2, command.payload().lines().size());
        assertEquals(second, command.payload().lines().get(1).productId());
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
    })
    void anythingThatIsNotAV1EnvelopeIsNonRetryable(String payload) {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(payload));
    }

    @Test
    void aNullValueIsNonRetryable() {
        NonRetryableEventException rejected =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(null));

        assertTrue(rejected.getMessage().contains("empty"));
    }

    // ---- version and type ----

    @Test
    void anUnsupportedSchemaVersionIsRejectedAndSaysSo() {
        String futureSchema = validEvent().replace("\"schemaVersion\":1", "\"schemaVersion\":9");

        NonRetryableEventException rejected =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(futureSchema));

        assertTrue(rejected.getMessage().contains("Unsupported schemaVersion 9"), rejected.getMessage());
    }

    @Test
    void anEventTypeThisConsumerDoesNotActOnIsRejected() {
        String otherType = validEvent()
                .replace("\"InventoryReleaseRequested\"", "\"InventoryReservationExpired\"");

        NonRetryableEventException rejected =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(otherType));

        assertTrue(rejected.getMessage().contains("Unsupported eventType"), rejected.getMessage());
    }

    // ---- structural payload validation ----

    @Test
    void aPayloadWithNoOrderIdIsRejected() {
        String noOrder = validEvent().replace("\"orderId\":\"" + ORDER_ID + "\"", "\"orderId\":null");

        NonRetryableEventException rejected =
                assertThrows(NonRetryableEventException.class, () -> parser.parse(noOrder));

        assertTrue(rejected.getMessage().contains("no orderId"), rejected.getMessage());
    }

    @Test
    void aPayloadWithNoLinesIsRejectedBecauseThereIsNothingToRelease() {
        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class,
                () -> parser.parse(eventWith(List.of())));

        assertTrue(rejected.getMessage().contains("no lines"), rejected.getMessage());
    }

    @Test
    void aLineWithNoProductIsRejected() {
        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class,
                () -> parser.parse(eventWith(List.of(new InventoryReleaseLine(null, 3)))));

        assertTrue(rejected.getMessage().contains("no productId"), rejected.getMessage());
    }

    /**
     * A zero or negative release would be either a no-op or a covert reservation increase. Both
     * mean the producer is confused, and neither is something to guess at.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    void aNonPositiveQuantityIsRejectedRatherThanInterpreted(int quantity) {
        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class,
                () -> parser.parse(eventWith(List.of(new InventoryReleaseLine(PRODUCT_ID, quantity)))));

        assertTrue(rejected.getMessage().contains("non-positive quantity"), rejected.getMessage());
    }

    /** No Java class name arriving over Kafka is ever resolved to a type. */
    @Test
    void typeMetadataInThePayloadIsIgnoredRatherThanHonoured() {
        String withTypeHints = validEvent()
                .replace("{\"eventId\"", "{\"@class\":\"java.lang.Runtime\",\"eventId\"");

        InventoryReleaseCommand command = parser.parse(withTypeHints);

        assertEquals(EVENT_ID, command.eventId(), "the envelope is read as plain data, type hints and all");
        assertEquals(ORDER_ID, command.orderId());
    }
}
