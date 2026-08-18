package com.sumedha.commerce.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockQuantityRequest(
        @NotNull
        @Min(1)
        Integer quantity
) {
}
