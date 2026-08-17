package com.sumedha.commerce.common.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilsTest {

    @Test
    void nowReturnsCurrentInstant() {
        Instant before = Instant.now();

        Instant result = TimeUtils.now();

        Instant after = Instant.now();

        assertFalse(result.isBefore(before));
        assertFalse(result.isAfter(after));
    }

    @Test
    void isExpiredReturnsTrueForPastInstant() {
        assertTrue(TimeUtils.isExpired(Instant.now().minusSeconds(60)));
    }

    @Test
    void isExpiredReturnsFalseForFutureInstant() {
        assertFalse(TimeUtils.isExpired(Instant.now().plusSeconds(60)));
    }

    @Test
    void isExpiredRejectsNull() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> TimeUtils.isExpired(null)
        );

        assertEquals("expiry must not be null", exception.getMessage());
    }

    @Test
    void isFutureReturnsTrueForFutureInstant() {
        assertTrue(TimeUtils.isFuture(Instant.now().plusSeconds(60)));
    }

    @Test
    void isFutureReturnsFalseForPastInstant() {
        assertFalse(TimeUtils.isFuture(Instant.now().minusSeconds(60)));
    }

    @Test
    void isFutureRejectsNull() {
        assertThrows(NullPointerException.class, () -> TimeUtils.isFuture(null));
    }

    @Test
    void constructorCannotBeUsed() throws NoSuchMethodException {
        Constructor<TimeUtils> constructor = TimeUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class,
                constructor::newInstance
        );

        Throwable cause = exception.getCause();
        assertInstanceOf(UnsupportedOperationException.class, cause);
        assertEquals("Utility class", cause.getMessage());
    }
}
