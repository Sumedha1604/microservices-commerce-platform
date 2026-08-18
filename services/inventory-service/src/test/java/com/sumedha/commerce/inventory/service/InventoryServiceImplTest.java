package com.sumedha.commerce.inventory.service;

import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock
    InventoryRepository inventories;

    InventoryServiceImpl service;
    UUID inventoryId;
    UUID productId;

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl(inventories);
        inventoryId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    @Test
    void createsInventory() {
        when(inventories.existsByProductId(productId)).thenReturn(false);
        when(inventories.save(any(Inventory.class))).thenAnswer(i -> i.getArgument(0));

        InventoryResponse response = service.create(new CreateInventoryRequest(productId, 10));

        assertEquals(productId, response.productId());
        assertEquals(10, response.quantity());
        verify(inventories).save(any(Inventory.class));
    }

    @Test
    void createRejectsDuplicateProductInventory() {
        when(inventories.existsByProductId(productId)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.create(new CreateInventoryRequest(productId, 10)));
        verify(inventories, never()).save(any());
    }

    @Test
    void createStartsReservedQuantityAtZero() {
        when(inventories.existsByProductId(productId)).thenReturn(false);
        when(inventories.save(any(Inventory.class))).thenAnswer(i -> i.getArgument(0));

        InventoryResponse response = service.create(new CreateInventoryRequest(productId, 10));

        assertEquals(0, response.reservedQuantity());
        assertEquals(10, response.availableQuantity());
    }

    @Test
    void getByIdReturnsInventory() {
        Inventory inventory = new Inventory(productId, 10);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.getById(inventory.getId());

        assertEquals(inventory.getId(), response.id());
        assertEquals(productId, response.productId());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(inventories.findById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(inventoryId));
    }

    @Test
    void getByProductIdReturnsInventory() {
        Inventory inventory = new Inventory(productId, 10);
        when(inventories.findByProductId(productId)).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.getByProductId(productId);

        assertEquals(productId, response.productId());
    }

    @Test
    void getByProductIdThrowsWhenMissing() {
        when(inventories.findByProductId(productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getByProductId(productId));
    }

    @Test
    void updatesQuantity() {
        Inventory inventory = new Inventory(productId, 10);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.updateQuantity(inventory.getId(), new UpdateInventoryQuantityRequest(25));

        assertEquals(25, response.quantity());
    }

    @Test
    void updateToExactlyReservedQuantityAllowed() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 4);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.updateQuantity(inventory.getId(), new UpdateInventoryQuantityRequest(4));

        assertEquals(4, response.quantity());
        assertEquals(4, response.reservedQuantity());
    }

    @Test
    void updateBelowReservedQuantityRejected() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 5);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        assertThrows(ConflictException.class,
                () -> service.updateQuantity(inventory.getId(), new UpdateInventoryQuantityRequest(4)));
    }

    @Test
    void updateThrowsWhenMissing() {
        when(inventories.findById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateQuantity(inventoryId, new UpdateInventoryQuantityRequest(5)));
    }

    @Test
    void reserveSucceeds() {
        Inventory inventory = new Inventory(productId, 10);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.reserve(inventory.getId(), new StockQuantityRequest(4));

        assertEquals(10, response.quantity());
        assertEquals(4, response.reservedQuantity());
        assertEquals(6, response.availableQuantity());
    }

    @Test
    void reserveExactlyAvailableQuantityAllowed() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 4);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.reserve(inventory.getId(), new StockQuantityRequest(6));

        assertEquals(10, response.quantity());
        assertEquals(10, response.reservedQuantity());
        assertEquals(0, response.availableQuantity());
    }

    @Test
    void reserveRejectsInsufficientAvailableQuantity() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 4);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        assertThrows(ConflictException.class,
                () -> service.reserve(inventory.getId(), new StockQuantityRequest(7)));
        assertEquals(4, inventory.getReservedQuantity());
    }

    @Test
    void reserveThrowsWhenMissing() {
        when(inventories.findById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.reserve(inventoryId, new StockQuantityRequest(1)));
    }

    @Test
    void reserveDoesNotChangeTotalQuantity() {
        Inventory inventory = new Inventory(productId, 10);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.reserve(inventory.getId(), new StockQuantityRequest(3));

        assertEquals(10, response.quantity());
    }

    @Test
    void releaseSucceeds() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 6);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.release(inventory.getId(), new StockQuantityRequest(2));

        assertEquals(10, response.quantity());
        assertEquals(4, response.reservedQuantity());
        assertEquals(6, response.availableQuantity());
    }

    @Test
    void releaseExactlyReservedQuantityAllowed() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 6);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.release(inventory.getId(), new StockQuantityRequest(6));

        assertEquals(0, response.reservedQuantity());
        assertEquals(10, response.availableQuantity());
    }

    @Test
    void releaseRejectsReleasingMoreThanReserved() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 6);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        assertThrows(ConflictException.class,
                () -> service.release(inventory.getId(), new StockQuantityRequest(7)));
        assertEquals(6, inventory.getReservedQuantity());
    }

    @Test
    void releaseThrowsWhenMissing() {
        when(inventories.findById(inventoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.release(inventoryId, new StockQuantityRequest(1)));
    }

    @Test
    void releaseDoesNotChangeTotalQuantity() {
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 6);
        when(inventories.findById(inventory.getId())).thenReturn(Optional.of(inventory));

        InventoryResponse response = service.release(inventory.getId(), new StockQuantityRequest(2));

        assertEquals(10, response.quantity());
    }
}
