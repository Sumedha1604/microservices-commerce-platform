package com.sumedha.commerce.common.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {

    @Test
    void successWithDataCreatesSuccessfulResponse() {
        Object data = new Object();

        ApiResponse<Object> response = ApiResponse.success(data);

        assertTrue(response.isSuccess());
        assertEquals("Success", response.getMessage());
        assertSame(data, response.getData());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void successWithMessageAndDataCreatesSuccessfulResponse() {
        String message = "Request completed";
        Object data = new Object();

        ApiResponse<Object> response = ApiResponse.success(message, data);

        assertTrue(response.isSuccess());
        assertEquals(message, response.getMessage());
        assertSame(data, response.getData());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void failureCreatesFailedResponse() {
        String message = "Request failed";

        ApiResponse<Object> response = ApiResponse.failure(message);

        assertFalse(response.isSuccess());
        assertEquals(message, response.getMessage());
        assertNull(response.getData());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void nullMessagesAreRejected() {
        Object data = new Object();

        assertThrows(NullPointerException.class, () -> ApiResponse.success(null, data));
        assertThrows(NullPointerException.class, () -> ApiResponse.failure(null));
    }
}
