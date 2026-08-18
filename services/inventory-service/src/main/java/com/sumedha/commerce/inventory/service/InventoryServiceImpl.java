package com.sumedha.commerce.inventory.service;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.mapper.InventoryMapper;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventories;

    public InventoryServiceImpl(InventoryRepository inventories) {
        this.inventories = inventories;
    }

    @Transactional
    public InventoryResponse create(CreateInventoryRequest request) {
        if (inventories.existsByProductId(request.productId())) {
            throw new ConflictException("Inventory already exists for product");
        }
        Inventory inventory = inventories.save(new Inventory(request.productId(), request.quantity()));
        return InventoryMapper.toResponse(inventory);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getById(UUID inventoryId) {
        return InventoryMapper.toResponse(inventory(inventoryId));
    }

    @Transactional(readOnly = true)
    public InventoryResponse getByProductId(UUID productId) {
        return InventoryMapper.toResponse(inventories.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory not found")));
    }

    @Transactional
    public InventoryResponse updateQuantity(UUID inventoryId, UpdateInventoryQuantityRequest request) {
        Inventory inventory = inventory(inventoryId);
        if (request.quantity() < inventory.getReservedQuantity()) {
            throw new ConflictException("Quantity cannot be below reserved quantity");
        }
        inventory.update(request.quantity(), inventory.getReservedQuantity());
        return InventoryMapper.toResponse(inventory);
    }

    @Transactional
    public InventoryResponse reserve(UUID inventoryId, StockQuantityRequest request) {
        Inventory inventory = inventory(inventoryId);
        int available = inventory.getQuantity() - inventory.getReservedQuantity();
        if (request.quantity() > available) {
            throw new ConflictException("Insufficient available quantity to reserve");
        }
        inventory.reserve(request.quantity());
        return InventoryMapper.toResponse(inventory);
    }

    @Transactional
    public InventoryResponse release(UUID inventoryId, StockQuantityRequest request) {
        Inventory inventory = inventory(inventoryId);
        if (request.quantity() > inventory.getReservedQuantity()) {
            throw new ConflictException("Cannot release more than reserved quantity");
        }
        inventory.release(request.quantity());
        return InventoryMapper.toResponse(inventory);
    }

    private Inventory inventory(UUID id) {
        return inventories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Inventory not found"));
    }
}
