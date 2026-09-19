package com.sumedha.commerce.recommendation.service;

import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The related-product ranking, stated in plain Java.
 *
 * <p>This is the executable specification of the algorithm: production ranks in SQL
 * ({@code RecommendationProductRepository#findRelated}, so a large category never has to be loaded
 * into memory), and an integration test proves that SQL returns exactly what this class returns for
 * the same catalogue.
 *
 * <p>Rules: exclude the source; keep only {@link RecommendationCandidate#recommendable() recommendable}
 * products that share the source's category or brand; score with {@link RecommendationScoring}; order
 * by score descending, then name ascending (binary, like PostgreSQL {@code COLLATE "C"}), then
 * productId ascending (its canonical string, which is PostgreSQL's uuid order); take {@code limit}.
 */
public final class RelatedProductRanker {

    static final Comparator<ProductRecommendation> ORDER = Comparator
            .comparingInt(ProductRecommendation::score).reversed()
            .thenComparing(ProductRecommendation::name)
            .thenComparing(recommendation -> recommendation.productId().toString());

    private RelatedProductRanker() {
    }

    public static List<ProductRecommendation> rank(RecommendationCandidate source,
                                                   List<RecommendationCandidate> catalogue, int limit) {
        Objects.requireNonNull(source, "source");
        return catalogue.stream()
                .filter(candidate -> !candidate.productId().equals(source.productId()))
                .filter(RecommendationCandidate::recommendable)
                .filter(candidate -> RecommendationScoring.sameCategory(source, candidate)
                        || RecommendationScoring.sameBrand(source, candidate))
                .map(candidate -> toRecommendation(source, candidate))
                .sorted(ORDER)
                .limit(limit)
                .toList();
    }

    private static ProductRecommendation toRecommendation(RecommendationCandidate source, RecommendationCandidate candidate) {
        boolean category = RecommendationScoring.sameCategory(source, candidate);
        boolean brand = RecommendationScoring.sameBrand(source, candidate);
        boolean price = RecommendationScoring.similarPrice(source, candidate);
        return ProductRecommendation.of(candidate, category, brand, price);
    }
}
