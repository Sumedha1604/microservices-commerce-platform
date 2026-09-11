package com.sumedha.commerce.search.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.metrics.SearchMetrics;
import com.sumedha.commerce.search.repository.ProductSearchDocumentRepository;
import com.sumedha.commerce.search.repository.ProductSearchDocumentRepository.SearchPage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Request validation, normalization and metrics. The matching and ranking themselves are SQL and are
 * proven against real PostgreSQL in {@code ProductSearchPostgresIntegrationTest}.
 */
class ProductSearchServiceTest {

    private ProductSearchDocumentRepository repository;
    private SimpleMeterRegistry registry;
    private ProductSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductSearchDocumentRepository.class);
        registry = new SimpleMeterRegistry();
        service = new ProductSearchService(repository, new SearchMetrics(registry));
    }

    private ProductSearchCriteria searchAndCapture(String q, String currency, String status, String sort) {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));
        service.search(q, null, null, null, null, currency, status, sort, 0, 20);
        ArgumentCaptor<ProductSearchCriteria> criteria = ArgumentCaptor.forClass(ProductSearchCriteria.class);
        verify(repository).search(criteria.capture());
        return criteria.getValue();
    }

    @Test
    void defaultsAreActiveProductsByRelevance() {
        ProductSearchCriteria criteria = searchAndCapture(null, null, null, null);

        assertNull(criteria.query());
        assertEquals("ACTIVE", criteria.status());
        assertEquals(SearchSort.RELEVANCE, criteria.sort());
        assertNull(criteria.currency());
        assertEquals(0, criteria.page());
        assertEquals(20, criteria.size());
    }

    @Test
    void inputIsNormalized() {
        ProductSearchCriteria criteria = searchAndCapture("  iPhone  ", " usd ", "draft", "PRICEDESC");

        assertEquals("iPhone", criteria.query());
        assertEquals("USD", criteria.currency());
        assertEquals("DRAFT", criteria.status());
        assertEquals(SearchSort.PRICE_DESC, criteria.sort());
    }

    @Test
    void aBlankQueryMeansBrowse() {
        assertNull(searchAndCapture("   ", null, null, null).query());
    }

    @Test
    void theRepositoryPageBecomesAPageResponse() {
        ProductSearchResult item = new ProductSearchResult(UUID.randomUUID(), "SKU", "Phone", "phone", null, null,
                UUID.randomUUID(), null, BigDecimal.TEN, "USD", "ACTIVE", 1L, Instant.now(), Instant.now());
        when(repository.search(any())).thenReturn(new SearchPage(List.of(item), 41));

        PageResponse<ProductSearchResult> page = service.search("phone", null, null, null, null, null, null, null, 2, 20);

        assertEquals(List.of(item), page.getItems());
        assertEquals(41, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
        assertEquals(false, page.isHasNext());
        assertEquals(1, registry.get("search.queries").tag("outcome", "success").counter().count());
        assertEquals(1, registry.get("search.query.duration").timer().count());
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 101"})
    void anOutOfRangePageIsRejected(int page, int size) {
        assertRejected(() -> service.search(null, null, null, null, null, null, null, null, page, size));
    }

    @Test
    void anOverlongQueryIsRejected() {
        String tooLong = "x".repeat(ProductSearchService.MAX_QUERY_LENGTH + 1);

        assertRejected(() -> service.search(tooLong, null, null, null, null, null, null, null, 0, 20));
    }

    @Test
    void invalidPriceRangesAreRejected() {
        assertRejected(() -> service.search(null, null, null, new BigDecimal("-1"), null, null, null, null, 0, 20));
        assertRejected(() -> service.search(null, null, null, null, new BigDecimal("-1"), null, null, null, 0, 20));
        assertRejected(() -> service.search(null, null, null, new BigDecimal("100"), new BigDecimal("10"), null, null, null, 0, 20));
    }

    @Test
    void invalidCurrencyStatusOrSortIsRejected() {
        assertRejected(() -> service.search(null, null, null, null, null, "dollars", null, null, 0, 20));
        assertRejected(() -> service.search(null, null, null, null, null, null, "ARCHIVED", null, 0, 20));
        assertRejected(() -> service.search(null, null, null, null, null, null, null, "popularity", 0, 20));
    }

    @Test
    void equalMinAndMaxPriceIsAllowed() {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));

        service.search(null, null, null, BigDecimal.TEN, BigDecimal.TEN, null, null, null, 0, 20);

        verify(repository).search(any());
    }

    private void assertRejected(Executable call) {
        assertThrows(BadRequestException.class, call::execute);
        verifyNoInteractions(repository);
        assertEquals(1, registry.get("search.queries").tag("outcome", "rejected").counter().count());
        registry.clear();
        registry = new SimpleMeterRegistry();
        service = new ProductSearchService(repository, new SearchMetrics(registry));
    }

    @FunctionalInterface
    private interface Executable {
        void execute();
    }
}
