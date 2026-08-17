package com.sumedha.commerce.common.core.enums;

import java.util.Locale;
import java.util.Objects;

public enum SortDirection {

    ASC,
    DESC;

    public static SortDirection from(String value) {
        Objects.requireNonNull(value, "value must not be null");

        try {
            return SortDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported sort direction: " + value);
        }
    }
}
