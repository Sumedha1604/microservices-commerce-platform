package com.sumedha.commerce.order.exception;

import com.sumedha.commerce.common.core.api.ErrorResponse;
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
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, "order-id"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, response.getBody().getStatusCode());
        assertEquals("CONFLICT", response.getBody().getErrorCode());
        assertEquals("Order was modified concurrently. Please retry.", response.getBody().getMessage());
    }

    @Test
    void mapsJpaOptimisticLockingFailureSubclassTo409() {
        ResponseEntity<ErrorResponse> response =
                handler.optimisticLock(new JpaOptimisticLockingFailureException(new OptimisticLockException("stale row")));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("Order was modified concurrently. Please retry.", response.getBody().getMessage());
    }

    @Test
    void doesNotExposeInternalExceptionDetails() {
        ResponseEntity<ErrorResponse> response =
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, "order-id"));

        String message = response.getBody().getMessage().toLowerCase();
        assertFalse(message.contains("hibernate"));
        assertFalse(message.contains("sql"));
        assertFalse(message.contains("stale"));
    }
}
