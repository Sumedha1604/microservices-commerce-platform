package com.sumedha.commerce.search.repository;

import com.sumedha.commerce.search.service.SearchSort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The only dynamic SQL fragments: LIKE escaping and the ORDER BY whitelist. */
class ProductSearchDocumentRepositoryTest {

    @Test
    void likeWildcardsInUserTextAreEscaped() {
        assertEquals("50\\% off\\_now\\\\", ProductSearchDocumentRepository.escapeLike("50% off_now\\"));
        assertEquals("phone", ProductSearchDocumentRepository.escapeLike("phone"));
    }

    @ParameterizedTest
    @EnumSource(SearchSort.class)
    void everySortEndsInAUniqueTieBreakerSoPagesNeverOverlap(SearchSort sort) {
        assertTrue(ProductSearchDocumentRepository.orderBy(sort, true).endsWith("d.product_id asc"));
        assertTrue(ProductSearchDocumentRepository.orderBy(sort, false).endsWith("d.product_id asc"));
    }

    @Test
    void relevanceUsesTheScoreOnlyWhenThereIsSearchText() {
        assertTrue(ProductSearchDocumentRepository.orderBy(SearchSort.RELEVANCE, true).startsWith("score desc"));
        assertEquals("d.name asc, d.product_id asc", ProductSearchDocumentRepository.orderBy(SearchSort.RELEVANCE, false));
    }

    @Test
    void sortParametersAreCaseInsensitiveAndDefaultToRelevance() {
        assertEquals(SearchSort.RELEVANCE, SearchSort.fromParameter(null));
        assertEquals(SearchSort.RELEVANCE, SearchSort.fromParameter(" "));
        assertEquals(SearchSort.PRICE_ASC, SearchSort.fromParameter("priceasc"));
        assertEquals(SearchSort.NAME_DESC, SearchSort.fromParameter("nameDesc"));
    }
}
