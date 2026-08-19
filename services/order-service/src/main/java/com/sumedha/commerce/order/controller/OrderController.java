package com.sumedha.commerce.order.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getById(@PathVariable("orderId") UUID orderId) {
        return ApiResponse.success(service.getById(orderId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<OrderResponse>> getByUserId(@PathVariable("userId") UUID userId) {
        return ApiResponse.success(service.getByUserId(userId));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirm(@PathVariable("orderId") UUID orderId) {
        return ApiResponse.success(service.confirm(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable("orderId") UUID orderId) {
        return ApiResponse.success(service.cancel(orderId));
    }
}
