package com.sumedha.commerce.inventory.service;

import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;

import java.util.UUID;

public interface InventoryService {

    InventoryResponse create(CreateInventoryRequest request);

    InventoryResponse getById(UUID inventoryId);

    InventoryResponse getByProductId(UUID productId);

    InventoryResponse updateQuantity(UUID inventoryId, UpdateInventoryQuantityRequest request);

    InventoryResponse reserve(UUID inventoryId, StockQuantityRequest request);

    InventoryResponse release(UUID inventoryId, StockQuantityRequest request);
}
