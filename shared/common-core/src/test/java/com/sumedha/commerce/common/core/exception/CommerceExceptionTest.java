package com.sumedha.commerce.common.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommerceExceptionTest {

    @Test
    void constructorStoresProvidedValues() {
        TestCommerceException exception = new TestCommerceException(
                "Something failed",
                "TEST_ERROR",
                400
        );

        assertEquals("Something failed", exception.getMessage());
        assertEquals("TEST_ERROR", exception.getErrorCode());
        assertEquals(400, exception.getStatusCode());
    }

    @Test
    void constructorRejectsNullMessage() {
        assertThrows(
                NullPointerException.class,
                () -> new TestCommerceException(null, "TEST_ERROR", 400)
        );
    }

    @Test
    void constructorRejectsNullErrorCode() {
        assertThrows(
                NullPointerException.class,
                () -> new TestCommerceException("Something failed", null, 400)
        );
    }

    @Test
    void constructorRejectsBlankErrorCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommerceException("Something failed", "", 400)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommerceException("Something failed", "   ", 400)
        );
    }

    @Test
    void constructorRejectsStatusCodeBelowClientErrorRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommerceException("Something failed", "TEST_ERROR", 399)
        );
    }

    @Test
    void constructorRejectsStatusCodeAboveServerErrorRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestCommerceException("Something failed", "TEST_ERROR", 600)
        );
    }

    @Test
    void constructorAcceptsBoundaryStatusCodes() {
        TestCommerceException clientError = new TestCommerceException(
                "Client error",
                "CLIENT_ERROR",
                400
        );
        TestCommerceException serverError = new TestCommerceException(
                "Server error",
                "SERVER_ERROR",
                599
        );

        assertEquals(400, clientError.getStatusCode());
        assertEquals(599, serverError.getStatusCode());
    }

    private static final class TestCommerceException extends CommerceException {

        private TestCommerceException(String message, String errorCode, int statusCode) {
            super(message, errorCode, statusCode);
        }
    }
}
