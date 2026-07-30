package com.sumedha.commerce.common.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrorResponseTest {

    @Test
    void ofCreatesErrorResponseWithCorrectValues() {
        ErrorResponse response = ErrorResponse.of("BAD_REQUEST", "Invalid input", 400);

        assertEquals("BAD_REQUEST", response.getErrorCode());
        assertEquals("Invalid input", response.getMessage());
        assertEquals(400, response.getStatusCode());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void ofRejectsNullErrorCode() {
        assertThrows(
                NullPointerException.class,
                () -> ErrorResponse.of(null, "Invalid input", 400)
        );
    }

    @Test
    void ofRejectsBlankErrorCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ErrorResponse.of("", "Invalid input", 400)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ErrorResponse.of("   ", "Invalid input", 400)
        );
    }

    @Test
    void ofRejectsNullMessage() {
        assertThrows(
                NullPointerException.class,
                () -> ErrorResponse.of("BAD_REQUEST", null, 400)
        );
    }

    @Test
    void ofRejectsStatusCodeBelowErrorRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ErrorResponse.of("BAD_REQUEST", "Invalid input", 399)
        );
    }

    @Test
    void ofRejectsStatusCodeAboveErrorRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ErrorResponse.of("INTERNAL_SERVER_ERROR", "Unexpected failure", 600)
        );
    }

    @Test
    void ofAcceptsBoundaryStatusCodes() {
        ErrorResponse clientError = ErrorResponse.of("CLIENT_ERROR", "Client error", 400);
        ErrorResponse serverError = ErrorResponse.of("SERVER_ERROR", "Server error", 599);

        assertEquals(400, clientError.getStatusCode());
        assertEquals(599, serverError.getStatusCode());
    }
}
