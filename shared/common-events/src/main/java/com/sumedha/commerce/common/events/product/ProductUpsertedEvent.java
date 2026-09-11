package com.sumedha.commerce.common.events.product;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The complete current state of one product's catalogue fields, after it was created or changed.
 *
 * <p>Projection-style rather than a diff: a consumer can replace its copy wholesale, so a missed
 * intermediate update never leaves a partially applied state. Carries only fields that exist on
 * product-service's {@code products} row; category and brand are referenced by id because their
 * names live in other rows that change independently.
 *
 * @param productId        the product (envelope partition key)
 * @param sku              unique stock-keeping unit; immutable after creation
 * @param name             display name
 * @param slug             unique URL slug
 * @param shortDescription optional short description
 * @param description      optional long description
 * @param categoryId       owning category
 * @param brandId          optional brand
 * @param price            exact decimal price (money - never a float)
 * @param currency         ISO-4217 code, e.g. {@code "USD"}
 * @param status           {@code DRAFT}, {@code ACTIVE}, {@code INACTIVE} or {@code DISCONTINUED}
 * @param active           product-service's independent active flag
 * @param version          the product row's optimistic-lock version; strictly increases with every
 *                         committed change, so consumers can reject an older state arriving late
 * @param updatedAt        when product-service last changed the row
 */
public record ProductUpsertedEvent(
        @JsonProperty("productId") UUID productId,
        @JsonProperty("sku") String sku,
        @JsonProperty("name") String name,
        @JsonProperty("slug") String slug,
        @JsonProperty("shortDescription") String shortDescription,
        @JsonProperty("description") String description,
        @JsonProperty("categoryId") UUID categoryId,
        @JsonProperty("brandId") UUID brandId,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("currency") String currency,
        @JsonProperty("status") String status,
        @JsonProperty("active") boolean active,
        @JsonProperty("version") long version,
        @JsonProperty("updatedAt") Instant updatedAt
) {
}
