package com.sumedha.commerce.cart.controller;

import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.service.CartService;
import com.sumedha.commerce.common.core.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/carts")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CartResponse>> createCart(@Valid @RequestBody CreateCartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createCart(request)));
    }

    @GetMapping("/{cartId}")
    public ApiResponse<CartResponse> getCartById(@PathVariable("cartId") UUID cartId) {
        return ApiResponse.success(service.getCartById(cartId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<CartResponse> getCartByUserId(@PathVariable("userId") UUID userId) {
        return ApiResponse.success(service.getCartByUserId(userId));
    }

    @PostMapping("/{cartId}/items")
    public ApiResponse<CartResponse> addItem(
            @PathVariable("cartId") UUID cartId,
            @Valid @RequestBody AddCartItemRequest request) {
        return ApiResponse.success(service.addItem(cartId, request));
    }

    @PatchMapping("/{cartId}/items/{productId}")
    public ApiResponse<CartResponse> updateItemQuantity(
            @PathVariable("cartId") UUID cartId,
            @PathVariable("productId") UUID productId,
            @Valid @RequestBody UpdateCartItemQuantityRequest request) {
        return ApiResponse.success(service.updateItemQuantity(cartId, productId, request));
    }

    @DeleteMapping("/{cartId}/items/{productId}")
    public ResponseEntity<Void> removeItem(
            @PathVariable("cartId") UUID cartId,
            @PathVariable("productId") UUID productId) {
        service.removeItem(cartId, productId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{cartId}/items")
    public ResponseEntity<Void> clearCart(@PathVariable("cartId") UUID cartId) {
        service.clearCart(cartId);
        return ResponseEntity.noContent().build();
    }
}
