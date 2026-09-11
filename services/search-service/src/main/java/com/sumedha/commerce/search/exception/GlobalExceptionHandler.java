package com.sumedha.commerce.search.exception;

import com.sumedha.commerce.common.core.api.ErrorResponse;
import com.sumedha.commerce.common.core.exception.CommerceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CommerceException.class)
    ResponseEntity<ErrorResponse> commerce(CommerceException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ErrorResponse.from(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "Invalid value for parameter '" + e.getName() + "'", 400));
    }

    /** The API is read-only; a write verb is a client error, not a server fault. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "Method " + e.getMethod() + " is not supported", 405));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ErrorResponse.of("RESOURCE_NOT_FOUND", "Resource not found", 404));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> error(Exception e) {
        return ResponseEntity.status(500).body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "An unexpected error occurred", 500));
    }
}
