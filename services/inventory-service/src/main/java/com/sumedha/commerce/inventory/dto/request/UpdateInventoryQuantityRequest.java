package com.sumedha.commerce.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateInventoryQuantityRequest(
        @NotNull
        @Min(0)
        Integer quantity
) {
}
