package com.sumedha.commerce.common.events.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON round-trip and wire shape of the product lifecycle payloads. */
class ProductEventPayloadTest {

    private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CATEGORY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private ProductUpsertedEvent upserted() {
        return new ProductUpsertedEvent(PRODUCT_ID, "SKU-PHONE-1", "Smart Phone X", "smart-phone-x",
                "A phone", "A very capable phone", CATEGORY_ID, null, new BigDecimal("599.9900"), "USD",
                "ACTIVE", true, 3L, Instant.parse("2026-09-11T10:15:30.123456Z"));
    }

    @Test
    void productUpsertedRoundTripsAsJsonWithExactPrice() throws Exception {
        ProductUpsertedEvent event = upserted();

        ProductUpsertedEvent back = mapper.readValue(mapper.writeValueAsString(event), ProductUpsertedEvent.class);

        assertEquals(event, back);
        assertEquals("599.9900", back.price().toPlainString());
        assertNull(back.brandId());
        assertEquals(3L, back.version());
    }

    @Test
    void productDeletedRoundTripsAsJson() throws Exception {
        ProductDeletedEvent event = new ProductDeletedEvent(PRODUCT_ID, 4L);

        ProductDeletedEvent back = mapper.readValue(mapper.writeValueAsString(event), ProductDeletedEvent.class);

        assertEquals(event, back);
    }

    @Test
    void theEnvelopeCarriesNoJavaTypeMetadata() throws Exception {
        String json = mapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.PRODUCT_UPSERTED,
                EventEnvelope.SCHEMA_VERSION_V1, Instant.now(), upserted()));

        assertTrue(json.contains("\"eventType\":\"ProductUpserted\""), json);
        assertTrue(json.contains("\"productId\":\"" + PRODUCT_ID + "\""), json);
        assertFalse(json.contains("com.sumedha"), json);
        assertFalse(json.contains("@class"), json);
    }
}
