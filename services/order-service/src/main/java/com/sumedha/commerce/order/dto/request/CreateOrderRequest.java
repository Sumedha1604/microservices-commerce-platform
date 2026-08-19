package com.sumedha.commerce.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull
        UUID userId,
        @NotBlank
        @Size(min = 3, max = 3)
        String currency,
        @NotEmpty
        @Valid
        List<CreateOrderItemRequest> items
) {
}
