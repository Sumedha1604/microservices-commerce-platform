package com.sumedha.commerce.cart.mapper;

import com.sumedha.commerce.cart.dto.response.CartItemResponse;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.entity.Cart;
import com.sumedha.commerce.cart.entity.CartItem;

import java.util.List;

public final class CartMapper {

    private CartMapper() {
    }

    public static CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
                item.getId(),
                item.getProductId(),
                item.getQuantity(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    public static CartResponse toResponse(Cart cart, List<CartItem> items) {
        return new CartResponse(
                cart.getId(),
                cart.getUserId(),
                items.stream().map(CartMapper::toItemResponse).toList(),
                cart.getCreatedAt(),
                cart.getUpdatedAt());
    }
}
