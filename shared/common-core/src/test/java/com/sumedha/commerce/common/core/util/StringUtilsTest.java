package com.sumedha.commerce.common.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringUtilsTest {

    @Test
    void isBlankReturnsTrueForNull() {
        assertTrue(StringUtils.isBlank(null));
    }

    @Test
    void isBlankReturnsTrueForEmptyString() {
        assertTrue(StringUtils.isBlank(""));
    }

    @Test
    void isBlankReturnsTrueForWhitespace() {
        assertTrue(StringUtils.isBlank("   "));
    }

    @Test
    void isBlankReturnsFalseForText() {
        assertFalse(StringUtils.isBlank("commerce"));
    }

    @Test
    void isNotBlankReturnsOppositeOfIsBlank() {
        assertTrue(StringUtils.isNotBlank("commerce"));
        assertFalse(StringUtils.isNotBlank("   "));
        assertFalse(StringUtils.isNotBlank(null));
    }

    @Test
    void requireNotBlankReturnsOriginalValue() {
        String value = "  commerce  ";

        String result = StringUtils.requireNotBlank(value, "value must not be blank");

        assertSame(value, result);
    }

    @Test
    void requireNotBlankRejectsNullValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StringUtils.requireNotBlank(null, "value must not be blank")
        );

        assertEquals("value must not be blank", exception.getMessage());
    }

    @Test
    void requireNotBlankRejectsEmptyValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StringUtils.requireNotBlank("", "value must not be blank")
        );
    }

    @Test
    void requireNotBlankRejectsWhitespaceValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StringUtils.requireNotBlank("   ", "value must not be blank")
        );
    }

    @Test
    void requireNotBlankRejectsNullMessage() {
        assertThrows(
                NullPointerException.class,
                () -> StringUtils.requireNotBlank("commerce", null)
        );
    }

    @Test
    void constructorCannotBeUsed() throws NoSuchMethodException {
        Constructor<StringUtils> constructor = StringUtils.class.getDeclaredConstructor();
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
