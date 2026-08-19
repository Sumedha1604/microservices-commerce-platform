package com.sumedha.commerce.checkout.client;

import java.time.Instant;

record DownstreamApiResponse<T>(
        boolean success,
        String message,
        T data,
        Instant timestamp
) {
}
