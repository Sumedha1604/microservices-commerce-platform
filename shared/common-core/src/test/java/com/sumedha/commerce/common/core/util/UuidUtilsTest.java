package com.sumedha.commerce.common.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidUtilsTest {

    private static final String VALID_UUID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void randomUuidReturnsValue() {
        assertNotNull(UuidUtils.randomUuid());
    }

    @Test
    void randomUuidReturnsDifferentValues() {
        UUID first = UuidUtils.randomUuid();
        UUID second = UuidUtils.randomUuid();

        assertNotEquals(first, second);
    }

    @Test
    void parseReturnsUuidForValidValue() {
        UUID expected = UUID.fromString(VALID_UUID);

        UUID result = UuidUtils.parse(VALID_UUID);

        assertEquals(expected, result);
    }

    @Test
    void parseRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> UuidUtils.parse(null));
    }

    @Test
    void parseRejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> UuidUtils.parse("   "));
    }

    @Test
    void parseRejectsInvalidUuid() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> UuidUtils.parse("not-a-uuid")
        );

        assertEquals("Invalid UUID: not-a-uuid", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void isValidReturnsTrueForValidUuid() {
        assertTrue(UuidUtils.isValid(VALID_UUID));
    }

    @Test
    void isValidReturnsFalseForInvalidUuid() {
        assertFalse(UuidUtils.isValid("invalid"));
    }

    @Test
    void isValidReturnsFalseForNull() {
        assertFalse(UuidUtils.isValid(null));
    }

    @Test
    void isValidReturnsFalseForBlank() {
        assertFalse(UuidUtils.isValid("   "));
    }

    @Test
    void constructorCannotBeUsed() throws NoSuchMethodException {
        Constructor<UuidUtils> constructor = UuidUtils.class.getDeclaredConstructor();
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
