package com.sumedha.commerce.cart.mapper;

import com.sumedha.commerce.cart.dto.response.CartItemResponse;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.entity.Cart;
import com.sumedha.commerce.cart.entity.CartItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartMapperTest {

    @Test
    void mapsItemResponse() {
        CartItem item = new CartItem(UUID.randomUUID(), UUID.randomUUID(), 3);

        CartItemResponse response = CartMapper.toItemResponse(item);

        assertEquals(item.getId(), response.id());
        assertEquals(item.getProductId(), response.productId());
        assertEquals(3, response.quantity());
        assertEquals(item.getCreatedAt(), response.createdAt());
        assertEquals(item.getUpdatedAt(), response.updatedAt());
    }

    @Test
    void mapsCartResponseWithItems() {
        UUID userId = UUID.randomUUID();
        Cart cart = new Cart(userId);
        CartItem item = new CartItem(cart.getId(), UUID.randomUUID(), 2);

        CartResponse response = CartMapper.toResponse(cart, List.of(item));

        assertEquals(cart.getId(), response.id());
        assertEquals(userId, response.userId());
        assertEquals(1, response.items().size());
        assertEquals(item.getId(), response.items().get(0).id());
    }

    @Test
    void mapsCartResponseWithEmptyItems() {
        Cart cart = new Cart(UUID.randomUUID());

        CartResponse response = CartMapper.toResponse(cart, List.of());

        assertTrue(response.items().isEmpty());
    }
}
