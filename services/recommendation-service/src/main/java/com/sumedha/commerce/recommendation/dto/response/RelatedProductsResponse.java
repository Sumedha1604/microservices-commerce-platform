package com.sumedha.commerce.recommendation.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Related products for one source product, best first.
 *
 * <p>{@code strategy} names the algorithm that produced the list ({@code CONTENT_BASED_V1}), so a
 * client can tell deterministic content-based results apart from any future strategy.
 */
public record RelatedProductsResponse(
        UUID sourceProductId,
        String strategy,
        int limit,
        List<ProductRecommendation> items
) {

    public static final String CONTENT_BASED_V1 = "CONTENT_BASED_V1";
}
