package com.sumedha.commerce.product.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.entity.ProductOutboxEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders a product's state into a durable outbox row: a fresh {@code eventId}, the full v1
 * envelope serialized once, and {@code productId} as the record key.
 *
 * <p>The product must already be flushed - its {@code version} and {@code updatedAt} are what
 * the database committed, not what the entity held before the write.
 */
@Component
public class ProductOutboxEventFactory {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ProductOutboxEvent productUpserted(Product product) {
        long version = Objects.requireNonNull(product.getVersion(), "product must be flushed before its event is built");
        return create(EventTypes.PRODUCT_UPSERTED, product.getId(), new ProductUpsertedEvent(
                product.getId(), product.getSku(), product.getName(), product.getSlug(),
                product.getShortDescription(), product.getDescription(), product.getCategoryId(),
                product.getBrandId(), product.getPrice(), product.getCurrency(), product.getStatus().name(),
                product.isActive(), version, product.getUpdatedAt()));
    }

    /** The deletion orders after the last committed state, hence {@code version + 1}. */
    public ProductOutboxEvent productDeleted(Product product) {
        long version = Objects.requireNonNull(product.getVersion(), "product must be loaded before its event is built");
        return create(EventTypes.PRODUCT_DELETED, product.getId(), new ProductDeletedEvent(product.getId(), version + 1));
    }

    private ProductOutboxEvent create(String eventType, UUID productId, Object eventPayload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        EventEnvelope<Object> envelope = new EventEnvelope<>(eventId, eventType,
                EventEnvelope.SCHEMA_VERSION_V1, occurredAt, eventPayload);
        return new ProductOutboxEvent(productId, eventId, eventType, EventEnvelope.SCHEMA_VERSION_V1,
                KafkaTopics.PRODUCT_EVENTS_V1, productId.toString(), objectMapper.writeValueAsString(envelope),
                occurredAt);
    }
}
