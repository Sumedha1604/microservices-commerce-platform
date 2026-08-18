package com.sumedha.commerce.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
        UUID id,
        UUID productId,
        int quantity,
        int reservedQuantity,
        int availableQuantity,
        Instant createdAt,
        Instant updatedAt
) {
}
