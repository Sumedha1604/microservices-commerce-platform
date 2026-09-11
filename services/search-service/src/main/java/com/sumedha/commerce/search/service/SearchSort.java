package com.sumedha.commerce.search.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The supported result orders. {@link #RELEVANCE} is the default. */
public enum SearchSort {

    RELEVANCE("relevance"),
    PRICE_ASC("priceAsc"),
    PRICE_DESC("priceDesc"),
    NAME_ASC("nameAsc"),
    NAME_DESC("nameDesc");

    private final String parameter;

    SearchSort(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }

    /** Blank means {@link #RELEVANCE}; matching is case-insensitive; anything else is a 400. */
    public static SearchSort fromParameter(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        return Arrays.stream(values())
                .filter(sort -> sort.parameter.equalsIgnoreCase(value.strip()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("sort must be one of "
                        + Arrays.stream(values()).map(SearchSort::parameter).collect(Collectors.joining(", "))));
    }
}
