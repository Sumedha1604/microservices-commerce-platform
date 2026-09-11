package com.sumedha.commerce.product.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.entity.ProductOutboxEvent;
import com.sumedha.commerce.product.enums.OutboxEventStatus;
import com.sumedha.commerce.product.enums.ProductStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductOutboxEventFactoryTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ProductOutboxEventFactory factory = new ProductOutboxEventFactory();
    private final UUID categoryId = UUID.randomUUID();

    private Product flushedProduct(long version) {
        Product product = new Product("SKU-PHONE-1", "Smart Phone X", "smart-phone-x", categoryId, null,
                new BigDecimal("599.9900"), "USD");
        product.update("Smart Phone X", "smart-phone-x", "A phone", "A very capable phone", categoryId, null,
                new BigDecimal("599.9900"), "USD", ProductStatus.ACTIVE, true);
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    @Test
    void productUpsertedIsAPendingV1EnvelopeKeyedByProductIdWithTheFullState() {
        Product product = flushedProduct(3L);

        ProductOutboxEvent row = factory.productUpserted(product);

        assertEquals("ProductUpserted", row.getEventType());
        assertEquals("Product", row.getAggregateType());
        assertEquals(product.getId(), row.getAggregateId());
        assertEquals(product.getId().toString(), row.getEventKey());
        assertEquals("product.events.v1", row.getTopic());
        assertEquals(1, row.getSchemaVersion());
        assertEquals(OutboxEventStatus.PENDING, row.getStatus());
        assertEquals(0, row.getAttemptCount());

        EventEnvelope<ProductUpsertedEvent> envelope =
                JSON.readValue(row.getPayload(), new TypeReference<EventEnvelope<ProductUpsertedEvent>>() {});
        assertEquals(row.getEventId(), envelope.eventId());
        assertEquals("ProductUpserted", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        ProductUpsertedEvent payload = envelope.payload();
        assertEquals(product.getId(), payload.productId());
        assertEquals("SKU-PHONE-1", payload.sku());
        assertEquals("Smart Phone X", payload.name());
        assertEquals("smart-phone-x", payload.slug());
        assertEquals("A phone", payload.shortDescription());
        assertEquals("A very capable phone", payload.description());
        assertEquals(categoryId, payload.categoryId());
        assertEquals("599.9900", payload.price().toPlainString(), "money keeps its exact scale");
        assertEquals("USD", payload.currency());
        assertEquals("ACTIVE", payload.status());
        assertEquals(true, payload.active());
        assertEquals(3L, payload.version());
        assertEquals(product.getUpdatedAt(), payload.updatedAt());
    }

    @Test
    void productDeletedOrdersAfterTheLastCommittedVersion() {
        Product product = flushedProduct(4L);

        ProductOutboxEvent row = factory.productDeleted(product);

        EventEnvelope<ProductDeletedEvent> envelope =
                JSON.readValue(row.getPayload(), new TypeReference<EventEnvelope<ProductDeletedEvent>>() {});
        assertEquals("ProductDeleted", row.getEventType());
        assertEquals(product.getId().toString(), row.getEventKey());
        assertEquals(product.getId(), envelope.payload().productId());
        assertEquals(5L, envelope.payload().version());
    }

    @Test
    void thePayloadCarriesNoJavaTypeMetadata() {
        String payload = factory.productUpserted(flushedProduct(0L)).getPayload();

        assertFalse(payload.contains("com.sumedha"), payload);
        assertFalse(payload.contains("@class"), payload);
    }

    @Test
    void everyEventGetsItsOwnEventId() {
        Product product = flushedProduct(0L);

        assertNotEquals(factory.productUpserted(product).getEventId(), factory.productUpserted(product).getEventId());
    }

    @Test
    void anUnflushedProductIsRefusedRatherThanPublishedWithoutAVersion() {
        Product unflushed = new Product("SKU-2", "Name", "name", categoryId, null, BigDecimal.ONE, "USD");

        assertThrows(NullPointerException.class, () -> factory.productUpserted(unflushed));
    }
}
