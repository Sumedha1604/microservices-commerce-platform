package com.sumedha.commerce.search.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.metrics.SearchMetrics;
import com.sumedha.commerce.search.repository.ProductSearchDocumentRepository;
import com.sumedha.commerce.search.repository.ProductSearchDocumentRepository.SearchPage;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Product search over the local read model. Never calls product-service: results reflect the
 * events applied so far, which is eventually consistent with the catalogue.
 *
 * <p><strong>Visibility.</strong> Only live (not deleted), {@code active} products are ever
 * returned, and by default only those with status {@code ACTIVE}. The {@code status} filter can
 * select another status, but inactive-flagged and deleted products never appear.
 */
@Service
public class ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_QUERY_LENGTH = 200;
    public static final String DEFAULT_STATUS = "ACTIVE";

    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE", "DISCONTINUED");
    private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");

    private final ProductSearchDocumentRepository documents;
    private final SearchMetrics metrics;

    public ProductSearchService(ProductSearchDocumentRepository documents, SearchMetrics metrics) {
        this.documents = documents;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSearchResult> search(String query, UUID categoryId, UUID brandId,
                                                    BigDecimal minPrice, BigDecimal maxPrice, String currency,
                                                    String status, String sort, int page, int size) {
        ProductSearchCriteria criteria;
        try {
            criteria = criteria(query, categoryId, brandId, minPrice, maxPrice, currency, status, sort, page, size);
        } catch (BadRequestException rejected) {
            metrics.queryRejected();
            throw rejected;
        }

        Timer.Sample sample = metrics.startQuery();
        SearchPage result = documents.search(criteria);
        long durationNanos = metrics.stopQuery(sample);

        log.info("Product search completed queryLength={} filters={} sort={} page={} size={} returned={} total={} durationMs={}",
                criteria.query() == null ? 0 : criteria.query().length(), filterCount(criteria),
                criteria.sort().parameter(), criteria.page(), criteria.size(), result.items().size(), result.total(),
                durationNanos / 1_000_000);
        return PageResponse.of(result.items(), criteria.page(), criteria.size(), result.total());
    }

    static ProductSearchCriteria criteria(String query, UUID categoryId, UUID brandId, BigDecimal minPrice,
                                          BigDecimal maxPrice, String currency, String status, String sort,
                                          int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.strip();
        if (normalizedQuery != null && normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        if (minPrice != null && minPrice.signum() < 0 || maxPrice != null && maxPrice.signum() < 0) {
            throw new BadRequestException("minPrice and maxPrice must not be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }
        String normalizedCurrency = null;
        if (currency != null && !currency.isBlank()) {
            if (!CURRENCY.matcher(currency.strip()).matches()) {
                throw new BadRequestException("currency must be a 3-letter code");
            }
            normalizedCurrency = currency.strip().toUpperCase(Locale.ROOT);
        }
        String normalizedStatus = DEFAULT_STATUS;
        if (status != null && !status.isBlank()) {
            normalizedStatus = status.strip().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalizedStatus)) {
                throw new BadRequestException("status must be one of DRAFT, ACTIVE, INACTIVE, DISCONTINUED");
            }
        }
        return new ProductSearchCriteria(normalizedQuery, categoryId, brandId, minPrice, maxPrice,
                normalizedCurrency, normalizedStatus, SearchSort.fromParameter(sort), page, size);
    }

    private static int filterCount(ProductSearchCriteria c) {
        int count = 0;
        if (c.categoryId() != null) count++;
        if (c.brandId() != null) count++;
        if (c.minPrice() != null) count++;
        if (c.maxPrice() != null) count++;
        if (c.currency() != null) count++;
        if (!DEFAULT_STATUS.equals(c.status())) count++;
        return count;
    }
}
