package com.sumedha.commerce.common.core.pagination;

import com.sumedha.commerce.common.core.constants.ApiConstants;
import com.sumedha.commerce.common.core.enums.SortDirection;
import com.sumedha.commerce.common.core.validation.ValidationUtils;

public final class PageRequest {

    private final int page;
    private final int size;
    private final SortDirection sortDirection;
    private final String sortBy;

    private PageRequest(
            int page,
            int size,
            SortDirection sortDirection,
            String sortBy
    ) {
        this.page = page;
        this.size = size;
        this.sortDirection = sortDirection;
        this.sortBy = sortBy;
    }

    public static PageRequest of(
            int page,
            int size,
            SortDirection sortDirection,
            String sortBy
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }

        ValidationUtils.requirePositive(size, "size");

        if (size > ApiConstants.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must not exceed " + ApiConstants.MAX_PAGE_SIZE
            );
        }

        return new PageRequest(
                page,
                size,
                ValidationUtils.requireNotNull(sortDirection, "sortDirection"),
                ValidationUtils.requireNotBlank(sortBy, "sortBy")
        );
    }

    public static PageRequest defaultPage(String sortBy) {
        return of(
                ApiConstants.DEFAULT_PAGE_NUMBER,
                ApiConstants.DEFAULT_PAGE_SIZE,
                SortDirection.ASC,
                sortBy
        );
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public SortDirection getSortDirection() {
        return sortDirection;
    }

    public String getSortBy() {
        return sortBy;
    }
}
