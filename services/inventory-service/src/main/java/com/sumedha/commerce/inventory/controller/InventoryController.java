package com.sumedha.commerce.inventory.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InventoryResponse>> create(@Valid @RequestBody CreateInventoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @GetMapping("/{inventoryId}")
    public ApiResponse<InventoryResponse> getById(@PathVariable("inventoryId") UUID inventoryId) {
        return ApiResponse.success(service.getById(inventoryId));
    }

    @GetMapping("/product/{productId}")
    public ApiResponse<InventoryResponse> getByProductId(@PathVariable("productId") UUID productId) {
        return ApiResponse.success(service.getByProductId(productId));
    }

    @PatchMapping("/{inventoryId}/quantity")
    public ApiResponse<InventoryResponse> updateQuantity(
            @PathVariable("inventoryId") UUID inventoryId,
            @Valid @RequestBody UpdateInventoryQuantityRequest request) {
        return ApiResponse.success(service.updateQuantity(inventoryId, request));
    }

    @PostMapping("/{inventoryId}/reserve")
    public ApiResponse<InventoryResponse> reserve(
            @PathVariable("inventoryId") UUID inventoryId,
            @Valid @RequestBody StockQuantityRequest request) {
        return ApiResponse.success(service.reserve(inventoryId, request));
    }

    @PostMapping("/{inventoryId}/release")
    public ApiResponse<InventoryResponse> release(
            @PathVariable("inventoryId") UUID inventoryId,
            @Valid @RequestBody StockQuantityRequest request) {
        return ApiResponse.success(service.release(inventoryId, request));
    }
}
