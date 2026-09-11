package com.sumedha.commerce.search.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.service.ProductSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only product search.
 *
 * <p>Public and unauthenticated, exactly like product-service's catalogue reads: it exposes nothing
 * that {@code GET /api/v1/products} does not already expose. There are no write endpoints - the read
 * model changes only through product events.
 */
@RestController
@RequestMapping("/api/v1/search")
public class ProductSearchController {

    private final ProductSearchService service;

    public ProductSearchController(ProductSearchService service) {
        this.service = service;
    }

    @GetMapping("/products")
    public ApiResponse<PageResponse<ProductSearchResult>> searchProducts(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "categoryId", required = false) UUID categoryId,
            @RequestParam(name = "brandId", required = false) UUID brandId,
            @RequestParam(name = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(name = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        return ApiResponse.success(
                service.search(q, categoryId, brandId, minPrice, maxPrice, currency, status, sort, page, size));
    }
}
