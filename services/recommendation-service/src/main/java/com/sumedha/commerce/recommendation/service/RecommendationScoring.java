package com.sumedha.commerce.recommendation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The related-product scoring rules, in one place.
 *
 * <p>Deterministic and content-based - no behavioural data, no model:
 * <ul>
 *   <li>{@link Reason#SAME_CATEGORY} +{@value #SAME_CATEGORY_POINTS}</li>
 *   <li>{@link Reason#SAME_BRAND} +{@value #SAME_BRAND_POINTS} (only when the source has a brand)</li>
 *   <li>{@link Reason#SIMILAR_PRICE} +{@value #SIMILAR_PRICE_POINTS} when the currencies match and the
 *       candidate's price is within {@link #PRICE_BAND} (20%) of the source's price</li>
 * </ul>
 * A candidate must share the category or the brand to be recommended at all; price similarity only
 * reorders related products, it never makes an unrelated one related. The weights are chosen so the
 * order of importance is strict: category alone (5) beats brand plus price (3), and brand alone (2)
 * beats price alone.
 *
 * <p>The ranking SQL in {@code RecommendationProductRepository} binds these same constants, and
 * {@link RelatedProductRanker} applies them in Java; an integration test proves both orderings agree.
 */
public final class RecommendationScoring {

    public static final int SAME_CATEGORY_POINTS = 5;
    public static final int SAME_BRAND_POINTS = 2;
    public static final int SIMILAR_PRICE_POINTS = 1;

    /** Candidate price within this fraction of the source price, same currency only. No FX conversion. */
    public static final BigDecimal PRICE_BAND = new BigDecimal("0.20");

    public enum Reason {
        SAME_CATEGORY,
        SAME_BRAND,
        SIMILAR_PRICE
    }

    private RecommendationScoring() {
    }

    public static boolean sameCategory(RecommendationCandidate source, RecommendationCandidate candidate) {
        return source.categoryId().equals(candidate.categoryId());
    }

    public static boolean sameBrand(RecommendationCandidate source, RecommendationCandidate candidate) {
        return source.brandId() != null && source.brandId().equals(candidate.brandId());
    }

    public static boolean similarPrice(RecommendationCandidate source, RecommendationCandidate candidate) {
        if (!source.currency().equalsIgnoreCase(candidate.currency())) {
            return false;
        }
        BigDecimal distance = candidate.price().subtract(source.price()).abs();
        return distance.compareTo(source.price().multiply(PRICE_BAND)) <= 0;
    }

    public static int score(boolean sameCategory, boolean sameBrand, boolean similarPrice) {
        return (sameCategory ? SAME_CATEGORY_POINTS : 0)
                + (sameBrand ? SAME_BRAND_POINTS : 0)
                + (similarPrice ? SIMILAR_PRICE_POINTS : 0);
    }

    public static List<Reason> reasons(boolean sameCategory, boolean sameBrand, boolean similarPrice) {
        List<Reason> reasons = new ArrayList<>(3);
        if (sameCategory) reasons.add(Reason.SAME_CATEGORY);
        if (sameBrand) reasons.add(Reason.SAME_BRAND);
        if (similarPrice) reasons.add(Reason.SIMILAR_PRICE);
        return List.copyOf(reasons);
    }
}
