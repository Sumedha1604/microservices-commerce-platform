package com.sumedha.commerce.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateInventoryRequest(
        @NotNull
        UUID productId,
        @NotNull
        @Min(0)
        Integer quantity
) {
}
