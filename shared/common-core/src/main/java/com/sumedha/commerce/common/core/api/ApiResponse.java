package com.sumedha.commerce.common.core.api;

import java.time.Instant;
import java.util.Objects;

public final class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final Instant timestamp;

    private ApiResponse(boolean success, String message, T data, Instant timestamp) {
        this.success = success;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.data = data;
        this.timestamp = timestamp;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public static <T> ApiResponse<T> success(T data) {
        return success("Success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, Instant.now());
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, message, null, Instant.now());
    }
}
