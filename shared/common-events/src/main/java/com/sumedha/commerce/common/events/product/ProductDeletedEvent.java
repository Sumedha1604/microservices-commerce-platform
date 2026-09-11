package com.sumedha.commerce.common.events.product;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * A product was permanently deleted from the catalogue.
 *
 * <p>{@code version} is one greater than the last committed row version, so the deletion orders
 * after every {@link ProductUpsertedEvent} for the same product and a consumer can refuse an older
 * upsert that arrives after it.
 *
 * @param productId the deleted product (envelope partition key)
 * @param version   last committed row version + 1
 */
public record ProductDeletedEvent(
        @JsonProperty("productId") UUID productId,
        @JsonProperty("version") long version
) {
}
