package com.sumedha.commerce.common.core.validation;

import com.sumedha.commerce.common.core.util.StringUtils;

import java.util.Objects;

public final class ValidationUtils {

    private ValidationUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String requireNotBlank(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");

        return StringUtils.requireNotBlank(
                value,
                fieldName + " must not be blank"
        );
    }

    public static <T> T requireNotNull(
            T value,
            String fieldName
    ) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");

        return Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );
    }

    public static int requirePositive(
            int value,
            String fieldName
    ) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");

        if (value <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive"
            );
        }

        return value;
    }
}
