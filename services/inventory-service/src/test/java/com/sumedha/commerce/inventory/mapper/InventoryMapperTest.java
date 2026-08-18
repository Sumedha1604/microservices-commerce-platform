package com.sumedha.commerce.inventory.mapper;

import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.entity.Inventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryMapperTest {

    @Test
    void mapsToResponse() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = new Inventory(productId, 20);
        inventory.update(20, 5);

        InventoryResponse response = InventoryMapper.toResponse(inventory);

        assertEquals(inventory.getId(), response.id());
        assertEquals(productId, response.productId());
        assertEquals(20, response.quantity());
        assertEquals(5, response.reservedQuantity());
        assertEquals(15, response.availableQuantity());
        assertEquals(inventory.getCreatedAt(), response.createdAt());
        assertEquals(inventory.getUpdatedAt(), response.updatedAt());
    }
}
