package com.sumedha.commerce.cart.service;

import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartResponse;

import java.util.UUID;

public interface CartService {

    CartResponse createCart(CreateCartRequest request);

    CartResponse getCartById(UUID cartId);

    CartResponse getCartByUserId(UUID userId);

    CartResponse addItem(UUID cartId, AddCartItemRequest request);

    CartResponse updateItemQuantity(UUID cartId, UUID productId, UpdateCartItemQuantityRequest request);

    CartResponse removeItem(UUID cartId, UUID productId);

    CartResponse clearCart(UUID cartId);
}
