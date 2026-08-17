package com.sumedha.commerce.common.core.validation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationUtilsTest {

    @Test
    void requireNotBlankReturnsOriginalValue() {
        String value = "  product  ";

        String result = ValidationUtils.requireNotBlank(value, "name");

        assertSame(value, result);
    }

    @Test
    void requireNotBlankRejectsNullValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ValidationUtils.requireNotBlank(null, "name")
        );

        assertEquals("name must not be blank", exception.getMessage());
    }

    @Test
    void requireNotBlankRejectsWhitespaceValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ValidationUtils.requireNotBlank("   ", "name")
        );

        assertEquals("name must not be blank", exception.getMessage());
    }

    @Test
    void requireNotBlankRejectsNullFieldName() {
        assertThrows(
                NullPointerException.class,
                () -> ValidationUtils.requireNotBlank("product", null)
        );
    }

    @Test
    void requireNotNullReturnsOriginalObject() {
        Object value = new Object();

        Object result = ValidationUtils.requireNotNull(value, "product");

        assertSame(value, result);
    }

    @Test
    void requireNotNullRejectsNullValue() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> ValidationUtils.requireNotNull(null, "product")
        );

        assertEquals("product must not be null", exception.getMessage());
    }

    @Test
    void requireNotNullRejectsNullFieldName() {
        assertThrows(
                NullPointerException.class,
                () -> ValidationUtils.requireNotNull(new Object(), null)
        );
    }

    @Test
    void requirePositiveReturnsOriginalValue() {
        assertEquals(5, ValidationUtils.requirePositive(5, "quantity"));
    }

    @Test
    void requirePositiveRejectsZero() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ValidationUtils.requirePositive(0, "quantity")
        );

        assertEquals("quantity must be positive", exception.getMessage());
    }

    @Test
    void requirePositiveRejectsNegativeValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ValidationUtils.requirePositive(-1, "quantity")
        );
    }

    @Test
    void requirePositiveRejectsNullFieldName() {
        assertThrows(
                NullPointerException.class,
                () -> ValidationUtils.requirePositive(5, null)
        );
    }

    @Test
    void constructorCannotBeUsed() throws NoSuchMethodException {
        Constructor<ValidationUtils> constructor = ValidationUtils.class.getDeclaredConstructor();
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
