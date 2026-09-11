package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;

import java.util.UUID;

/**
 * A validated inbound product event: envelope identity plus a payload bound to its contract type.
 * Produced by {@link ProductEventParser}; by the time one exists every field the projection needs is
 * present and in range.
 */
public sealed interface ProductEvent {

    UUID eventId();

    String eventType();

    UUID productId();

    long version();

    record Upserted(UUID eventId, ProductUpsertedEvent payload) implements ProductEvent {

        @Override
        public String eventType() {
            return EventTypes.PRODUCT_UPSERTED;
        }

        @Override
        public UUID productId() {
            return payload.productId();
        }

        @Override
        public long version() {
            return payload.version();
        }
    }

    record Deleted(UUID eventId, ProductDeletedEvent payload) implements ProductEvent {

        @Override
        public String eventType() {
            return EventTypes.PRODUCT_DELETED;
        }

        @Override
        public UUID productId() {
            return payload.productId();
        }

        @Override
        public long version() {
            return payload.version();
        }
    }
}
