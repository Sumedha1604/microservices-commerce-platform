package com.sumedha.commerce.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.exception.GlobalExceptionHandler;
import com.sumedha.commerce.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryControllerTest {

    MockMvc mvc;
    InventoryService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID inventoryId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(InventoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InventoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private InventoryResponse response(int quantity, int reservedQuantity) {
        Instant now = Instant.now();
        return new InventoryResponse(inventoryId, productId, quantity, reservedQuantity, quantity - reservedQuantity, now, now);
    }

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        when(service.create(any())).thenReturn(response(10, 0));

        CreateInventoryRequest request = new CreateInventoryRequest(productId, 10);
        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(inventoryId.toString()))
                .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.quantity").value(10));

        verify(service).create(any());
    }

    @Test
    void createRejectsNullProductId() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(null, 10);

        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsNegativeQuantity() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(productId, -1);

        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createMapsConflictExceptionWhenInventoryAlreadyExists() throws Exception {
        when(service.create(any())).thenThrow(new ConflictException("Inventory already exists for product"));

        CreateInventoryRequest request = new CreateInventoryRequest(productId, 10);
        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Inventory already exists for product"));
    }

    @Test
    void createMapsUnexpectedErrorToSanitizedResponse() throws Exception {
        when(service.create(any())).thenThrow(new RuntimeException("db connection string leaked here"));

        CreateInventoryRequest request = new CreateInventoryRequest(productId, 10);
        mvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void getByIdReturnsInventory() throws Exception {
        when(service.getById(inventoryId)).thenReturn(response(10, 2));

        mvc.perform(get("/api/v1/inventory/{inventoryId}", inventoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(inventoryId.toString()))
                .andExpect(jsonPath("$.data.availableQuantity").value(8));
    }

    @Test
    void getByIdMapsResourceNotFound() throws Exception {
        when(service.getById(inventoryId)).thenThrow(new ResourceNotFoundException("Inventory not found"));

        mvc.perform(get("/api/v1/inventory/{inventoryId}", inventoryId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Inventory not found"));
    }

    @Test
    void getByIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/inventory/{inventoryId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void getByProductIdReturnsInventory() throws Exception {
        when(service.getByProductId(productId)).thenReturn(response(10, 0));

        mvc.perform(get("/api/v1/inventory/product/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId.toString()));
    }

    @Test
    void getByProductIdMapsResourceNotFound() throws Exception {
        when(service.getByProductId(productId)).thenThrow(new ResourceNotFoundException("Inventory not found"));

        mvc.perform(get("/api/v1/inventory/product/{productId}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Inventory not found"));
    }

    @Test
    void getByProductIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/inventory/product/{productId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateQuantityReturnsUpdatedInventory() throws Exception {
        when(service.updateQuantity(eq(inventoryId), any())).thenReturn(response(20, 2));

        UpdateInventoryQuantityRequest request = new UpdateInventoryQuantityRequest(20);
        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(20));

        verify(service).updateQuantity(eq(inventoryId), any());
    }

    @Test
    void updateQuantityRejectsNegativeQuantity() throws Exception {
        UpdateInventoryQuantityRequest request = new UpdateInventoryQuantityRequest(-5);

        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateQuantityMapsConflictExceptionWhenBelowReserved() throws Exception {
        when(service.updateQuantity(eq(inventoryId), any()))
                .thenThrow(new ConflictException("Quantity cannot be below reserved quantity"));

        UpdateInventoryQuantityRequest request = new UpdateInventoryQuantityRequest(1);
        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Quantity cannot be below reserved quantity"));
    }

    @Test
    void updateQuantityMapsResourceNotFound() throws Exception {
        when(service.updateQuantity(eq(inventoryId), any()))
                .thenThrow(new ResourceNotFoundException("Inventory not found"));

        UpdateInventoryQuantityRequest request = new UpdateInventoryQuantityRequest(20);
        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updateQuantityRejectsMalformedJsonBody() throws Exception {
        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateQuantityWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        UpdateInventoryQuantityRequest request = new UpdateInventoryQuantityRequest(20);
        mvc.perform(patch("/api/v1/inventory/{inventoryId}/quantity", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void reserveReturnsUpdatedInventory() throws Exception {
        when(service.reserve(eq(inventoryId), any())).thenReturn(response(10, 4));

        StockQuantityRequest request = new StockQuantityRequest(4);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/reserve", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(10))
                .andExpect(jsonPath("$.data.reservedQuantity").value(4))
                .andExpect(jsonPath("$.data.availableQuantity").value(6));

        verify(service).reserve(eq(inventoryId), any());
    }

    @Test
    void reserveRejectsInvalidQuantity() throws Exception {
        StockQuantityRequest request = new StockQuantityRequest(0);

        mvc.perform(post("/api/v1/inventory/{inventoryId}/reserve", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void reserveMapsConflictExceptionWhenInsufficientStock() throws Exception {
        when(service.reserve(eq(inventoryId), any()))
                .thenThrow(new ConflictException("Insufficient available quantity to reserve"));

        StockQuantityRequest request = new StockQuantityRequest(100);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/reserve", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Insufficient available quantity to reserve"));
    }

    @Test
    void reserveMapsResourceNotFound() throws Exception {
        when(service.reserve(eq(inventoryId), any())).thenThrow(new ResourceNotFoundException("Inventory not found"));

        StockQuantityRequest request = new StockQuantityRequest(4);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/reserve", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void releaseReturnsUpdatedInventory() throws Exception {
        when(service.release(eq(inventoryId), any())).thenReturn(response(10, 2));

        StockQuantityRequest request = new StockQuantityRequest(2);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/release", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(10))
                .andExpect(jsonPath("$.data.reservedQuantity").value(2))
                .andExpect(jsonPath("$.data.availableQuantity").value(8));

        verify(service).release(eq(inventoryId), any());
    }

    @Test
    void releaseRejectsInvalidQuantity() throws Exception {
        StockQuantityRequest request = new StockQuantityRequest(-1);

        mvc.perform(post("/api/v1/inventory/{inventoryId}/release", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void releaseMapsConflictExceptionWhenReleasingTooMuch() throws Exception {
        when(service.release(eq(inventoryId), any()))
                .thenThrow(new ConflictException("Cannot release more than reserved quantity"));

        StockQuantityRequest request = new StockQuantityRequest(100);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/release", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Cannot release more than reserved quantity"));
    }

    @Test
    void releaseMapsResourceNotFound() throws Exception {
        when(service.release(eq(inventoryId), any())).thenThrow(new ResourceNotFoundException("Inventory not found"));

        StockQuantityRequest request = new StockQuantityRequest(2);
        mvc.perform(post("/api/v1/inventory/{inventoryId}/release", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void reserveRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/inventory/{inventoryId}/reserve", inventoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }
}
