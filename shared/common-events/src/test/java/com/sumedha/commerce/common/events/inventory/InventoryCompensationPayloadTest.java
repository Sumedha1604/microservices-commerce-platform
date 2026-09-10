package com.sumedha.commerce.common.events.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract for the compensation payload.
 *
 * <p>The JSON shape is asserted field by field, not just round-tripped, because this is the
 * document two independently deployed services agree on: order-service writes it into a durable
 * outbox row that may be published minutes later, and inventory-service parses it without ever
 * seeing the producing code. A renamed field is a broken deployment, not a failed test.
 */
class InventoryCompensationPayloadTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PRODUCT_A = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PRODUCT_B = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Envelope-level checks use Jackson 3, which is what Spring Boot 4 actually serialises the
     * outbox payload with; the payload-only checks above stay on the Jackson 2 mapper the other
     * payload contract tests use.
     */
    private final tools.jackson.databind.ObjectMapper runtimeMapper = JsonMapper.builder().build();

    private InventoryReleaseRequestedEvent event() {
        return new InventoryReleaseRequestedEvent(ORDER_ID, "payment failed",
                List.of(new InventoryReleaseLine(PRODUCT_A, 3), new InventoryReleaseLine(PRODUCT_B, 1)));
    }

    @Test
    void exposesEveryConstructorField() {
        InventoryReleaseRequestedEvent event = event();

        assertEquals(ORDER_ID, event.orderId());
        assertEquals("payment failed", event.reason());
        assertEquals(2, event.lines().size());
        assertEquals(PRODUCT_A, event.lines().getFirst().productId());
        assertEquals(3, event.lines().getFirst().quantity());
    }

    @Test
    void roundTripsAsJson() throws Exception {
        InventoryReleaseRequestedEvent event = event();

        assertEquals(event, mapper.readValue(mapper.writeValueAsString(event),
                InventoryReleaseRequestedEvent.class));
    }

    /** The exact key names a consumer binds against. */
    @Test
    void serialisesWithTheAgreedFieldNames() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(event()));

        assertEquals(ORDER_ID.toString(), json.get("orderId").asText());
        assertEquals("payment failed", json.get("reason").asText());
        assertTrue(json.get("lines").isArray());
        assertEquals(PRODUCT_A.toString(), json.get("lines").get(0).get("productId").asText());
        assertEquals(3, json.get("lines").get(0).get("quantity").asInt());
        assertEquals(3, json.size(), "unexpected extra top-level fields: " + json);
    }

    /**
     * No Java type metadata may appear on the wire. The consumer picks its payload record from
     * the {@code eventType} string alone; a class name in the JSON would be a deserialization
     * gadget waiting to happen.
     */
    @Test
    void carriesNoJavaTypeMetadata() throws Exception {
        String json = runtimeMapper.writeValueAsString(new EventEnvelope<>(EVENT_ID,
                EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1,
                Instant.parse("2026-09-10T12:00:00Z"), event()));

        assertFalse(json.contains("@class"), json);
        assertFalse(json.contains("__TypeId__"), json);
        assertFalse(json.contains("com.sumedha"), json);
    }

    @Test
    void ridesInsideAStandardV1Envelope() throws Exception {
        EventEnvelope<InventoryReleaseRequestedEvent> envelope = new EventEnvelope<>(EVENT_ID,
                EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1,
                Instant.parse("2026-09-10T12:00:00Z"), event());

        JsonNode json = mapper.readTree(runtimeMapper.writeValueAsString(envelope));

        assertEquals(EVENT_ID.toString(), json.get("eventId").asText());
        assertEquals("InventoryReleaseRequested", json.get("eventType").asText());
        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals(ORDER_ID.toString(), json.get("payload").get("orderId").asText());
    }

    @Test
    void aSingleLineOrderIsRepresentableAndSoIsALargeQuantity() {
        InventoryReleaseRequestedEvent single = new InventoryReleaseRequestedEvent(ORDER_ID, "payment failed",
                List.of(new InventoryReleaseLine(PRODUCT_A, Integer.MAX_VALUE)));

        assertEquals(1, single.lines().size());
        assertEquals(Integer.MAX_VALUE, single.lines().getFirst().quantity());
    }
}
