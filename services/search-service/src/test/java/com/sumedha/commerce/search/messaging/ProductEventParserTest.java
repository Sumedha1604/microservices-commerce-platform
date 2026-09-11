package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Envelope and payload validation for product events: anything not exactly readable is non-retryable. */
class ProductEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CATEGORY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-11T10:15:30Z");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ProductEventParser parser = new ProductEventParser();

    private String upserted(ProductUpsertedEvent payload) {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PRODUCT_UPSERTED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT, payload));
    }

    private ProductUpsertedEvent payload(String name, String price, String currency, String status, long version) {
        return new ProductUpsertedEvent(PRODUCT_ID, "SKU-PHONE-1", name, "smart-phone-x", "A phone",
                "A very capable phone", CATEGORY_ID, null, price == null ? null : new BigDecimal(price), currency,
                status, true, version, Instant.parse("2026-09-11T10:15:00Z"));
    }

    private String upserted() {
        return upserted(payload("Smart Phone X", "599.9900", "USD", "ACTIVE", 3L));
    }

    private String deleted() {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PRODUCT_DELETED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT, new ProductDeletedEvent(PRODUCT_ID, 4L)));
    }

    // ---- valid ----

    @Test
    void parsesAValidProductUpserted() {
        ProductEvent.Upserted event = assertInstanceOf(ProductEvent.Upserted.class, parser.parse(upserted()));

        assertEquals(EVENT_ID, event.eventId());
        assertEquals("ProductUpserted", event.eventType());
        assertEquals(PRODUCT_ID, event.productId());
        assertEquals(3L, event.version());
        assertEquals("Smart Phone X", event.payload().name());
        assertEquals("SKU-PHONE-1", event.payload().sku());
        assertEquals(CATEGORY_ID, event.payload().categoryId());
        assertEquals("ACTIVE", event.payload().status());
        assertEquals("599.9900", event.payload().price().toPlainString(), "price keeps its exact scale");
    }

    @Test
    void parsesAValidProductDeleted() {
        ProductEvent.Deleted event = assertInstanceOf(ProductEvent.Deleted.class, parser.parse(deleted()));

        assertEquals(EVENT_ID, event.eventId());
        assertEquals("ProductDeleted", event.eventType());
        assertEquals(PRODUCT_ID, event.productId());
        assertEquals(4L, event.version());
    }

    @Test
    void versionZeroIsAValidInitialState() {
        assertEquals(0L, parser.parse(upserted(payload("Smart Phone X", "1.00", "USD", "DRAFT", 0L))).version());
    }

    // ---- malformed ----

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "{this is not json", "not json at all", "[]", "\"a bare string\"", "42", "{}",
            "{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}",
            "{\"eventId\":\"not-a-uuid\",\"eventType\":\"ProductUpserted\",\"schemaVersion\":1,"
                    + "\"occurredAt\":\"2026-09-11T10:15:30Z\",\"payload\":{}}",
    })
    void anythingThatIsNotAV1EnvelopeIsNonRetryable(String value) {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(value));
    }

    @Test
    void aNullValueIsNonRetryable() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(null));
    }

    @Test
    void aPayloadThatIsNotAnObjectIsRejected() {
        String value = upserted().replaceFirst("\"payload\":\\{.*}}$", "\"payload\":\"text\"}");
        assertNotEquals(upserted(), value);

        assertThrows(NonRetryableEventException.class, () -> parser.parse(value));
    }

    // ---- schema and type ----

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 9})
    void anUnsupportedSchemaVersionIsRejected(int version) {
        String other = upserted().replace("\"schemaVersion\":1", "\"schemaVersion\":" + version);

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(other));

        assertTrue(rejected.getMessage().contains("Unsupported schemaVersion " + version), rejected.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ProductCreated", "PaymentAuthorized", "productupserted", ""})
    void anUnknownEventTypeIsRejected(String eventType) {
        String other = upserted().replace("\"ProductUpserted\"", "\"" + eventType + "\"");

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class, () -> parser.parse(other));

        assertTrue(rejected.getMessage().contains("Unsupported eventType"), rejected.getMessage());
    }

    // ---- required fields ----

    @Test
    void aMissingProductIdIsRejectedForBothTypes() {
        String upsertWithout = upserted().replace("\"productId\":\"" + PRODUCT_ID + "\"", "\"productId\":null");
        String deleteWithout = deleted().replace("\"productId\":\"" + PRODUCT_ID + "\"", "\"productId\":null");

        assertTrue(assertThrows(NonRetryableEventException.class, () -> parser.parse(upsertWithout))
                .getMessage().contains("no productId"));
        assertTrue(assertThrows(NonRetryableEventException.class, () -> parser.parse(deleteWithout))
                .getMessage().contains("no productId"));
    }

    /** A primitive would silently read an absent version as 0 - a valid, applicable state. */
    @Test
    void anAbsentVersionIsRejectedRatherThanReadAsZero() {
        String upsertWithout = upserted().replace("\"version\":3,", "");
        String deleteWithout = deleted().replace(",\"version\":4", "");
        assertNotEquals(upserted(), upsertWithout);
        assertNotEquals(deleted(), deleteWithout);

        assertThrows(NonRetryableEventException.class, () -> parser.parse(upsertWithout));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(deleteWithout));
    }

    @Test
    void aNegativeOrNonIntegerVersionIsRejected() {
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted(payload("Smart Phone X", "1.00", "USD", "ACTIVE", -1L))));
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"version\":3", "\"version\":\"three\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sku", "name", "slug"})
    void aMissingOrBlankTextFieldIsRejected(String field) {
        String original = upserted();
        String blank = original.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\"  \"");
        String missing = original.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":null");
        assertNotEquals(original, blank);

        assertTrue(assertThrows(NonRetryableEventException.class, () -> parser.parse(blank))
                .getMessage().contains("no " + field));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(missing));
    }

    @Test
    void invalidPayloadValuesAreRejected() {
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted(payload("Smart Phone X", null, "USD", "ACTIVE", 1L))), "missing price");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted(payload("Smart Phone X", "-0.01", "USD", "ACTIVE", 1L))), "negative price");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted(payload("Smart Phone X", "1.00", "US", "ACTIVE", 1L))), "bad currency");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted(payload("Smart Phone X", "1.00", "USD", "ARCHIVED", 1L))), "unknown status");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"categoryId\":\"" + CATEGORY_ID + "\"", "\"categoryId\":null")),
                "missing category");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"active\":true,", "")), "missing active flag");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"price\":599.9900", "\"price\":\"cheap\"")), "non-numeric price");
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"categoryId\":\"" + CATEGORY_ID + "\"", "\"categoryId\":\"nope\"")),
                "malformed uuid");
    }

    // ---- no type metadata ----

    @Test
    void typeMetadataInTheRecordIsIgnoredRatherThanHonoured() {
        String withHints = upserted()
                .replace("{\"eventId\"", "{\"@class\":\"java.lang.Runtime\",\"__TypeId__\":\"java.lang.ProcessBuilder\",\"eventId\"")
                .replace("{\"productId\"", "{\"@type\":\"java.net.URL\",\"productId\"");

        ProductEvent event = parser.parse(withHints);

        assertInstanceOf(ProductEvent.Upserted.class, event, "the eventType string alone chooses the payload type");
        assertEquals(PRODUCT_ID, event.productId());
    }
}
