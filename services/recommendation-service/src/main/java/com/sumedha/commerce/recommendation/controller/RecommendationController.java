package com.sumedha.commerce.recommendation.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.recommendation.dto.response.RelatedProductsResponse;
import com.sumedha.commerce.recommendation.service.RecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only recommendations.
 *
 * <p>Public and unauthenticated, like product-service's catalogue reads and search: it exposes only
 * catalogue data those APIs already expose. There is no popular/trending endpoint - the platform has
 * no trustworthy purchase signal yet (see ADR 0007).
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<RelatedProductsResponse> relatedProducts(
            @PathVariable("productId") UUID productId,
            @RequestParam(name = "limit", defaultValue = "" + RecommendationService.DEFAULT_LIMIT) int limit) {
        return ApiResponse.success(service.relatedProducts(productId, limit));
    }
}
