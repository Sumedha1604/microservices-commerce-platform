package com.sumedha.commerce.search.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One product as the search read model holds it.
 *
 * <p>{@code version} and {@code updatedAt} are product-service's; {@code indexedAt} is when this
 * service last applied a change, which makes the eventual-consistency lag visible to a caller.
 * Category and brand are ids only - their names are not part of the product event.
 */
public record ProductSearchResult(
        UUID productId,
        String sku,
        String name,
        String slug,
        String shortDescription,
        String description,
        UUID categoryId,
        UUID brandId,
        BigDecimal price,
        String currency,
        String status,
        long version,
        Instant updatedAt,
        Instant indexedAt
) {
}
