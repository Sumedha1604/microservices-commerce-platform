package com.sumedha.commerce.common.core.pagination;

import java.util.List;
import java.util.Objects;

public final class PageResponse<T> {

    private final List<T> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean hasNext;
    private final boolean hasPrevious;

    private PageResponse(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext,
            boolean hasPrevious
    ) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.hasNext = hasNext;
        this.hasPrevious = hasPrevious;
    }

    public List<T> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public boolean isHasPrevious() {
        return hasPrevious;
    }

    public static <T> PageResponse<T> of(
            List<T> items,
            int page,
            int size,
            long totalElements
    ) {
        Objects.requireNonNull(items, "items must not be null");

        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be zero or greater");
        }

        int totalPages = totalElements == 0
                ? 0
                : Math.toIntExact(1 + (totalElements - 1) / size);
        boolean hasNext = page < totalPages - 1;
        boolean hasPrevious = page > 0;

        return new PageResponse<>(
                List.copyOf(items),
                page,
                size,
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }
}
