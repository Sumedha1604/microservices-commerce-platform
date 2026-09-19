package com.sumedha.commerce.recommendation.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.dto.response.RelatedProductsResponse;
import com.sumedha.commerce.recommendation.metrics.RecommendationMetrics;
import com.sumedha.commerce.recommendation.repository.RecommendationProductRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Related-product recommendations from the local projection. Never calls another service.
 *
 * <p>A source that is missing from the projection, or deleted, is a {@code 404} - the platform's
 * convention for a resource looked up by id. A live source that is inactive or not {@code ACTIVE}
 * still gets recommendations (e.g. alternatives for a discontinued product); candidates are always
 * live, active and {@code ACTIVE}.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 10;

    private final RecommendationProductRepository products;
    private final RecommendationMetrics metrics;

    public RecommendationService(RecommendationProductRepository products, RecommendationMetrics metrics) {
        this.products = products;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public RelatedProductsResponse relatedProducts(UUID sourceProductId, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            metrics.requestRejected();
            throw new BadRequestException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }

        Timer.Sample sample = metrics.startRequest();
        if (products.findLive(sourceProductId).isEmpty()) {
            metrics.stopRequest(sample, RecommendationMetrics.Outcome.NOT_FOUND);
            log.info("Related products requested for unknown sourceProductId={}", sourceProductId);
            throw new ResourceNotFoundException("Product not found in the recommendation catalogue: " + sourceProductId);
        }

        List<ProductRecommendation> items = products.findRelated(sourceProductId, limit);
        long durationNanos = metrics.stopRequest(sample, RecommendationMetrics.Outcome.SUCCESS);
        log.info("Related products computed sourceProductId={} limit={} returned={} durationMs={}",
                sourceProductId, limit, items.size(), durationNanos / 1_000_000);
        return new RelatedProductsResponse(sourceProductId, RelatedProductsResponse.CONTENT_BASED_V1, limit, items);
    }
}
