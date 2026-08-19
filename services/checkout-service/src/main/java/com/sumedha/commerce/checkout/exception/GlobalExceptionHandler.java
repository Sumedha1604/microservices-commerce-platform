package com.sumedha.commerce.checkout.exception;

import com.sumedha.commerce.common.core.api.ErrorResponse;
import com.sumedha.commerce.common.core.exception.CommerceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CommerceException.class)
    ResponseEntity<ErrorResponse> commerce(CommerceException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(ErrorResponse.from(exception));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", "Request validation failed", 400));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "An unexpected error occurred", 500));
    }
}
