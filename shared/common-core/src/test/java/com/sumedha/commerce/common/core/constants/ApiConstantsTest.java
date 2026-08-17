package com.sumedha.commerce.common.core.constants;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiConstantsTest {

    @Test
    void constantsHaveExpectedValues() {
        assertEquals("Success", ApiConstants.DEFAULT_SUCCESS_MESSAGE);
        assertEquals("An unexpected error occurred", ApiConstants.DEFAULT_ERROR_MESSAGE);
        assertEquals(0, ApiConstants.DEFAULT_PAGE_NUMBER);
        assertEquals(20, ApiConstants.DEFAULT_PAGE_SIZE);
        assertEquals(100, ApiConstants.MAX_PAGE_SIZE);
    }

    @Test
    void defaultPageSizeDoesNotExceedMaximum() {
        assertTrue(ApiConstants.DEFAULT_PAGE_SIZE <= ApiConstants.MAX_PAGE_SIZE);
    }

    @Test
    void constructorCannotBeUsed() throws NoSuchMethodException {
        Constructor<ApiConstants> constructor = ApiConstants.class.getDeclaredConstructor();
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
