package com.sumedha.commerce.order.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderItemRequest(
        @NotNull
        UUID productId,
        @NotBlank
        String productName,
        String sku,
        @NotNull
        @DecimalMin("0.00")
        BigDecimal unitPrice,
        @NotNull
        @Min(1)
        Integer quantity
) {
}
