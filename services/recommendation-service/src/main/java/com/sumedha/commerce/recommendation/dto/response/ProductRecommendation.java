package com.sumedha.commerce.recommendation.dto.response;

import com.sumedha.commerce.recommendation.service.RecommendationCandidate;
import com.sumedha.commerce.recommendation.service.RecommendationScoring;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One recommended product, with the score and the plain-language reasons that produced it
 * ({@code SAME_CATEGORY}, {@code SAME_BRAND}, {@code SIMILAR_PRICE}).
 */
public record ProductRecommendation(
        UUID productId,
        String name,
        String slug,
        UUID categoryId,
        UUID brandId,
        BigDecimal price,
        String currency,
        int score,
        List<RecommendationScoring.Reason> reasons
) {

    public static ProductRecommendation of(RecommendationCandidate candidate, boolean sameCategory,
                                           boolean sameBrand, boolean similarPrice) {
        return new ProductRecommendation(candidate.productId(), candidate.name(), candidate.slug(),
                candidate.categoryId(), candidate.brandId(), candidate.price(), candidate.currency(),
                RecommendationScoring.score(sameCategory, sameBrand, similarPrice),
                RecommendationScoring.reasons(sameCategory, sameBrand, similarPrice));
    }
}
