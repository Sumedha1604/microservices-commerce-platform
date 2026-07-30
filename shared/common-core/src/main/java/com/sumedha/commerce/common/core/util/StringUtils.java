package com.sumedha.commerce.common.core.util;

import java.util.Objects;

public final class StringUtils {

    private StringUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    public static String requireNotBlank(
            String value,
            String message
    ) {
        Objects.requireNonNull(message, "message must not be null");

        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }

        return value;
    }
}
