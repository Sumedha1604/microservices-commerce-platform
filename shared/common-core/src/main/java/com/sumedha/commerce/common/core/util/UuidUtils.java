package com.sumedha.commerce.common.core.util;

import java.util.Objects;
import java.util.UUID;

public final class UuidUtils {

    private UuidUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static UUID randomUuid() {
        return UUID.randomUUID();
    }

    public static UUID parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        StringUtils.requireNotBlank(value, "value must not be blank");

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid UUID: " + value,
                    exception
            );
        }
    }

    public static boolean isValid(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }

        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
