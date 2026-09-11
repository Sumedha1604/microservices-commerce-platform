package com.sumedha.commerce.search.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A validated, normalized search request: {@code query} is stripped or null, {@code currency} is
 * upper-case or null, {@code status} is always set (default {@code ACTIVE}).
 */
public record ProductSearchCriteria(
        String query,
        UUID categoryId,
        UUID brandId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String currency,
        String status,
        SearchSort sort,
        int page,
        int size
) {
}
