package com.sumedha.commerce.recommendation.messaging;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation of product events for the recommendation projection. */
class ProductEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CATEGORY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID BRAND_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-11T10:15:30Z");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ProductEventParser parser = new ProductEventParser();

    private ProductUpsertedEvent payload(String price, String currency, String status, long version) {
        return new ProductUpsertedEvent(PRODUCT_ID, "SKU-1", "Smart Phone X", "smart-phone-x", null, "desc",
                CATEGORY_ID, BRAND_ID, price == null ? null : new BigDecimal(price), currency, status, true, version,
                Instant.parse("2026-09-11T10:15:00Z"));
    }

    private String upserted(ProductUpsertedEvent payload) {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PRODUCT_UPSERTED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT, payload));
    }

    private String upserted() {
        return upserted(payload("599.9900", "USD", "ACTIVE", 3L));
    }

    private String deleted() {
        return json.writeValueAsString(new EventEnvelope<>(EVENT_ID, EventTypes.PRODUCT_DELETED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT, new ProductDeletedEvent(PRODUCT_ID, 4L)));
    }

    @Test
    void parsesAValidProductUpserted() {
        ProductEvent.Upserted event = assertInstanceOf(ProductEvent.Upserted.class, parser.parse(upserted()));

        assertEquals(EVENT_ID, event.eventId());
        assertEquals("ProductUpserted", event.eventType());
        assertEquals(PRODUCT_ID, event.productId());
        assertEquals(3L, event.version());
        assertEquals(CATEGORY_ID, event.payload().categoryId());
        assertEquals(BRAND_ID, event.payload().brandId());
        assertEquals("599.9900", event.payload().price().toPlainString(), "price keeps its exact scale");
    }

    @Test
    void aProductWithoutABrandIsValid() {
        String noBrand = upserted().replace("\"brandId\":\"" + BRAND_ID + "\"", "\"brandId\":null");

        assertNull(((ProductEvent.Upserted) parser.parse(noBrand)).payload().brandId());
    }

    @Test
    void parsesAValidProductDeleted() {
        ProductEvent.Deleted event = assertInstanceOf(ProductEvent.Deleted.class, parser.parse(deleted()));

        assertEquals(PRODUCT_ID, event.productId());
        assertEquals(4L, event.version());
        assertEquals("ProductDeleted", event.eventType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{this is not json", "[]", "42", "{}", "\"text\"",
            "{\"eventId\":\"not-a-uuid\",\"eventType\":\"ProductUpserted\",\"schemaVersion\":1,"
                    + "\"occurredAt\":\"2026-09-11T10:15:30Z\",\"payload\":{}}"})
    void malformedRecordsAreNonRetryable(String value) {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(value));
    }

    @Test
    void aNullValueIsNonRetryable() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 9})
    void anUnsupportedSchemaVersionIsRejected(int version) {
        String other = upserted().replace("\"schemaVersion\":1", "\"schemaVersion\":" + version);

        assertTrue(assertThrows(NonRetryableEventException.class, () -> parser.parse(other))
                .getMessage().contains("Unsupported schemaVersion " + version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ProductCreated", "PaymentAuthorized", "productupserted"})
    void anUnknownEventTypeIsRejected(String eventType) {
        String other = upserted().replace("\"ProductUpserted\"", "\"" + eventType + "\"");

        assertTrue(assertThrows(NonRetryableEventException.class, () -> parser.parse(other))
                .getMessage().contains("Unsupported eventType"));
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        String original = upserted();
        String[] broken = {
                original.replace("\"productId\":\"" + PRODUCT_ID + "\"", "\"productId\":null"),
                original.replace("\"name\":\"Smart Phone X\"", "\"name\":\"  \""),
                original.replace("\"slug\":\"smart-phone-x\"", "\"slug\":null"),
                original.replace("\"categoryId\":\"" + CATEGORY_ID + "\"", "\"categoryId\":null"),
                original.replace("\"active\":true,", ""),
                original.replace("\"price\":599.9900", "\"price\":null"),
        };
        for (String record : broken) {
            assertNotEquals(original, record, "fixture must actually break the record");
            assertThrows(NonRetryableEventException.class, () -> parser.parse(record), record);
        }
        String deleteWithoutProduct = deleted().replace("\"productId\":\"" + PRODUCT_ID + "\"", "\"productId\":null");
        assertThrows(NonRetryableEventException.class, () -> parser.parse(deleteWithoutProduct));
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted(payload("-1.00", "USD", "ACTIVE", 1L))));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted(payload("1.00", "DOLLAR", "ACTIVE", 1L))));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted(payload("1.00", "USD", "ARCHIVED", 1L))));
        assertThrows(NonRetryableEventException.class,
                () -> parser.parse(upserted().replace("\"categoryId\":\"" + CATEGORY_ID + "\"", "\"categoryId\":\"nope\"")));
    }

    /** A primitive would read an absent version as 0 - a valid, applicable state. */
    @Test
    void anAbsentNegativeOrNonIntegerVersionIsRejected() {
        String upsertWithout = upserted().replace("\"version\":3,", "");
        String deleteWithout = deleted().replace(",\"version\":4", "");
        assertNotEquals(upserted(), upsertWithout);
        assertNotEquals(deleted(), deleteWithout);

        assertThrows(NonRetryableEventException.class, () -> parser.parse(upsertWithout));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(deleteWithout));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted(payload("1.00", "USD", "ACTIVE", -1L))));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted().replace("\"version\":3", "\"version\":3.5")));
        assertThrows(NonRetryableEventException.class, () -> parser.parse(upserted().replace("\"version\":3", "\"version\":\"3\"")));
    }

    @Test
    void typeMetadataInTheRecordIsIgnored() {
        String withHints = upserted().replace("{\"eventId\"", "{\"@class\":\"java.lang.Runtime\",\"eventId\"");

        assertInstanceOf(ProductEvent.Upserted.class, parser.parse(withHints));
    }
}
