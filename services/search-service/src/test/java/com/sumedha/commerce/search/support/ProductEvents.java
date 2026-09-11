package com.sumedha.commerce.search.support;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Builds product event records exactly as product-service serializes them. */
public final class ProductEvents {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private ProductEvents() {
    }

    /** A mutable description of one product state, rendered into a ProductUpserted record. */
    public static final class Product {
        public UUID productId = UUID.randomUUID();
        public String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        public String name = "Product";
        public String slug = "product-" + UUID.randomUUID();
        public String shortDescription;
        public String description;
        public UUID categoryId = UUID.randomUUID();
        public UUID brandId;
        public BigDecimal price = new BigDecimal("10.00");
        public String currency = "USD";
        public String status = "ACTIVE";
        public boolean active = true;
        public long version;

        public Product name(String value) { name = value; return this; }
        public Product sku(String value) { sku = value; return this; }
        public Product shortDescription(String value) { shortDescription = value; return this; }
        public Product description(String value) { description = value; return this; }
        public Product category(UUID value) { categoryId = value; return this; }
        public Product brand(UUID value) { brandId = value; return this; }
        public Product price(String value) { price = new BigDecimal(value); return this; }
        public Product currency(String value) { currency = value; return this; }
        public Product status(String value) { status = value; return this; }
        public Product active(boolean value) { active = value; return this; }
        public Product version(long value) { version = value; return this; }

        public ProductUpsertedEvent payload() {
            return new ProductUpsertedEvent(productId, sku, name, slug, shortDescription, description, categoryId,
                    brandId, price, currency, status, active, version, Instant.now());
        }
    }

    public static Product product() {
        return new Product();
    }

    public static String upserted(UUID eventId, Product product) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PRODUCT_UPSERTED,
                EventEnvelope.SCHEMA_VERSION_V1, Instant.now(), product.payload()));
    }

    public static String deleted(UUID eventId, UUID productId, long version) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PRODUCT_DELETED,
                EventEnvelope.SCHEMA_VERSION_V1, Instant.now(), new ProductDeletedEvent(productId, version)));
    }
}
