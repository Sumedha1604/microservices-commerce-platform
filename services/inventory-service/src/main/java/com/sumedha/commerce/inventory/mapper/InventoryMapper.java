package com.sumedha.commerce.inventory.mapper;

import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.entity.Inventory;

public final class InventoryMapper {

    private InventoryMapper() {
    }

    public static InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getQuantity(),
                inventory.getReservedQuantity(),
                inventory.getAvailableQuantity(),
                inventory.getCreatedAt(),
                inventory.getUpdatedAt());
    }
}
