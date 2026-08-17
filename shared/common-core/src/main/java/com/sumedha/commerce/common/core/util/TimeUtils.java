package com.sumedha.commerce.common.core.util;

import java.time.Instant;
import java.util.Objects;

public final class TimeUtils {

    private TimeUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Instant now() {
        return Instant.now();
    }

    public static boolean isExpired(Instant expiry) {
        Objects.requireNonNull(expiry, "expiry must not be null");

        return expiry.isBefore(Instant.now());
    }

    public static boolean isFuture(Instant value) {
        Objects.requireNonNull(value, "value must not be null");

        return value.isAfter(Instant.now());
    }
}
