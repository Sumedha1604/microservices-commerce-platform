package com.sumedha.commerce.payment.exception;

import com.sumedha.commerce.common.core.api.ErrorResponse;
import com.sumedha.commerce.common.core.exception.ConflictException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsOptimisticLockingFailureTo409() {
        ResponseEntity<ErrorResponse> response =
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, "payment-id"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, response.getBody().getStatusCode());
        assertEquals("CONFLICT", response.getBody().getErrorCode());
        assertEquals("Payment was modified concurrently. Please retry.", response.getBody().getMessage());
    }

    @Test
    void mapsJpaOptimisticLockingFailureSubclassTo409() {
        ResponseEntity<ErrorResponse> response =
                handler.optimisticLock(new JpaOptimisticLockingFailureException(new OptimisticLockException("stale row")));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("Payment was modified concurrently. Please retry.", response.getBody().getMessage());
    }

    @Test
    void mapsConflictExceptionTo409() {
        ResponseEntity<ErrorResponse> response = handler.commerce(new ConflictException("Only pending payments can be authorized"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONFLICT", response.getBody().getErrorCode());
        assertEquals("Only pending payments can be authorized", response.getBody().getMessage());
    }

    @Test
    void mapsUnexpectedExceptionTo500WithSanitizedResponse() {
        ResponseEntity<ErrorResponse> response = handler.error(new RuntimeException("jdbc:postgresql://internal-host/payment_db"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_SERVER_ERROR", response.getBody().getErrorCode());
        assertEquals("An unexpected error occurred", response.getBody().getMessage());
    }

    @Test
    void doesNotExposeInternalExceptionDetails() {
        ResponseEntity<ErrorResponse> response =
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, "payment-id"));

        String message = response.getBody().getMessage().toLowerCase();
        assertFalse(message.contains("hibernate"));
        assertFalse(message.contains("sql"));
        assertFalse(message.contains("stale"));
    }
}
