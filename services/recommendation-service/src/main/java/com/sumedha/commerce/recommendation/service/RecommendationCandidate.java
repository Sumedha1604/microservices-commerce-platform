package com.sumedha.commerce.recommendation.service;

import java.math.BigDecimal;
import java.util.UUID;

/** One product as the recommendation projection holds it. */
public record RecommendationCandidate(
        UUID productId,
        String name,
        String slug,
        UUID categoryId,
        UUID brandId,
        BigDecimal price,
        String currency,
        String status,
        boolean active,
        boolean deleted
) {

    /** Only live, active, {@code ACTIVE} products are ever recommended. */
    public boolean recommendable() {
        return !deleted && active && "ACTIVE".equals(status);
    }
}
