package com.sumedha.commerce.common.core.pagination;

import com.sumedha.commerce.common.core.constants.ApiConstants;
import com.sumedha.commerce.common.core.enums.SortDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageRequestTest {

    @Test
    void ofCreatesPageRequestWithProvidedValues() {
        PageRequest request = PageRequest.of(
                2,
                25,
                SortDirection.DESC,
                "createdAt"
        );

        assertEquals(2, request.getPage());
        assertEquals(25, request.getSize());
        assertEquals(SortDirection.DESC, request.getSortDirection());
        assertEquals("createdAt", request.getSortBy());
    }

    @Test
    void defaultPageUsesConfiguredDefaults() {
        PageRequest request = PageRequest.defaultPage("id");

        assertEquals(ApiConstants.DEFAULT_PAGE_NUMBER, request.getPage());
        assertEquals(ApiConstants.DEFAULT_PAGE_SIZE, request.getSize());
        assertEquals(SortDirection.ASC, request.getSortDirection());
        assertEquals("id", request.getSortBy());
    }

    @Test
    void ofRejectsNegativePage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(-1, 25, SortDirection.ASC, "id")
        );
    }

    @Test
    void ofRejectsZeroSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(0, 0, SortDirection.ASC, "id")
        );
    }

    @Test
    void ofRejectsSizeAboveMaximum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(
                        0,
                        ApiConstants.MAX_PAGE_SIZE + 1,
                        SortDirection.ASC,
                        "id"
                )
        );
    }

    @Test
    void ofAcceptsMaximumPageSize() {
        PageRequest request = PageRequest.of(
                0,
                ApiConstants.MAX_PAGE_SIZE,
                SortDirection.ASC,
                "id"
        );

        assertEquals(ApiConstants.MAX_PAGE_SIZE, request.getSize());
    }

    @Test
    void ofRejectsNullSortDirection() {
        assertThrows(
                NullPointerException.class,
                () -> PageRequest.of(0, 25, null, "id")
        );
    }

    @Test
    void ofRejectsNullSortBy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(0, 25, SortDirection.ASC, null)
        );
    }

    @Test
    void ofRejectsBlankSortBy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.of(0, 25, SortDirection.ASC, "   ")
        );
    }

    @Test
    void defaultPageRejectsBlankSortBy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PageRequest.defaultPage("   ")
        );
    }
}
