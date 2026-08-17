package com.sumedha.commerce.common.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandardExceptionsTest {

    @Test
    void badRequestExceptionHasCorrectValues() {
        BadRequestException exception = new BadRequestException("Invalid request");

        assertEquals("Invalid request", exception.getMessage());
        assertEquals("BAD_REQUEST", exception.getErrorCode());
        assertEquals(400, exception.getStatusCode());
    }

    @Test
    void unauthorizedExceptionHasCorrectValues() {
        UnauthorizedException exception = new UnauthorizedException("Authentication required");

        assertEquals("Authentication required", exception.getMessage());
        assertEquals("UNAUTHORIZED", exception.getErrorCode());
        assertEquals(401, exception.getStatusCode());
    }

    @Test
    void forbiddenExceptionHasCorrectValues() {
        ForbiddenException exception = new ForbiddenException("Access denied");

        assertEquals("Access denied", exception.getMessage());
        assertEquals("FORBIDDEN", exception.getErrorCode());
        assertEquals(403, exception.getStatusCode());
    }

    @Test
    void resourceNotFoundExceptionHasCorrectValues() {
        ResourceNotFoundException exception = new ResourceNotFoundException("Product not found");

        assertEquals("Product not found", exception.getMessage());
        assertEquals("RESOURCE_NOT_FOUND", exception.getErrorCode());
        assertEquals(404, exception.getStatusCode());
    }

    @Test
    void conflictExceptionHasCorrectValues() {
        ConflictException exception = new ConflictException("Resource already exists");

        assertEquals("Resource already exists", exception.getMessage());
        assertEquals("CONFLICT", exception.getErrorCode());
        assertEquals(409, exception.getStatusCode());
    }

    @Test
    void internalServerExceptionHasCorrectValues() {
        InternalServerException exception = new InternalServerException("Unexpected failure");

        assertEquals("Unexpected failure", exception.getMessage());
        assertEquals("INTERNAL_SERVER_ERROR", exception.getErrorCode());
        assertEquals(500, exception.getStatusCode());
    }

    @Test
    void standardExceptionsRejectNullMessages() {
        assertThrows(NullPointerException.class, () -> new BadRequestException(null));
        assertThrows(NullPointerException.class, () -> new UnauthorizedException(null));
        assertThrows(NullPointerException.class, () -> new ForbiddenException(null));
        assertThrows(NullPointerException.class, () -> new ResourceNotFoundException(null));
        assertThrows(NullPointerException.class, () -> new ConflictException(null));
        assertThrows(NullPointerException.class, () -> new InternalServerException(null));
    }
}
