package com.sumedha.commerce.cart.service;

import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.entity.Cart;
import com.sumedha.commerce.cart.entity.CartItem;
import com.sumedha.commerce.cart.repository.CartItemRepository;
import com.sumedha.commerce.cart.repository.CartRepository;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    CartRepository carts;

    @Mock
    CartItemRepository items;

    CartServiceImpl service;
    UUID cartId;
    UUID userId;
    UUID productId;

    @BeforeEach
    void setUp() {
        service = new CartServiceImpl(carts, items);
        cartId = UUID.randomUUID();
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    @Test
    void createsCart() {
        when(carts.existsByUserId(userId)).thenReturn(false);
        when(carts.saveAndFlush(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
        when(items.findByCartId(any())).thenReturn(List.of());

        CartResponse response = service.createCart(new CreateCartRequest(userId));

        assertEquals(userId, response.userId());
        assertTrue(response.items().isEmpty());
        verify(carts).saveAndFlush(any(Cart.class));
    }

    @Test
    void createRejectsDuplicateUserCart() {
        when(carts.existsByUserId(userId)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.createCart(new CreateCartRequest(userId)));
        verify(carts, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsRaceLostToConcurrentDuplicateUserId() {
        when(carts.existsByUserId(userId)).thenReturn(false);
        when(carts.saveAndFlush(any(Cart.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThrows(ConflictException.class, () -> service.createCart(new CreateCartRequest(userId)));
    }

    @Test
    void getByIdReturnsCart() {
        Cart cart = new Cart(userId);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartId(cart.getId())).thenReturn(List.of());

        CartResponse response = service.getCartById(cart.getId());

        assertEquals(cart.getId(), response.id());
        assertEquals(userId, response.userId());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCartById(cartId));
    }

    @Test
    void getByUserIdReturnsCart() {
        Cart cart = new Cart(userId);
        when(carts.findByUserId(userId)).thenReturn(Optional.of(cart));
        when(items.findByCartId(cart.getId())).thenReturn(List.of());

        CartResponse response = service.getCartByUserId(userId);

        assertEquals(userId, response.userId());
    }

    @Test
    void getByUserIdThrowsWhenMissing() {
        when(carts.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCartByUserId(userId));
    }

    @Test
    void addItemCreatesNewItem() {
        Cart cart = new Cart(userId);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.empty());
        when(items.findByCartId(cart.getId())).thenReturn(List.of(new CartItem(cart.getId(), productId, 2)));

        CartResponse response = service.addItem(cart.getId(), new AddCartItemRequest(productId, 2));

        verify(items).save(any(CartItem.class));
        assertEquals(1, response.items().size());
        assertEquals(2, response.items().get(0).quantity());
    }

    @Test
    void addItemIncrementsExistingQuantity() {
        Cart cart = new Cart(userId);
        CartItem existing = new CartItem(cart.getId(), productId, 3);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.of(existing));
        when(items.findByCartId(cart.getId())).thenReturn(List.of(existing));

        service.addItem(cart.getId(), new AddCartItemRequest(productId, 2));

        assertEquals(5, existing.getQuantity());
        verify(items, never()).save(any());
    }

    @Test
    void addItemThrowsWhenCartMissing() {
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.addItem(cartId, new AddCartItemRequest(productId, 1)));
    }

    @Test
    void updateItemQuantitySucceeds() {
        Cart cart = new Cart(userId);
        CartItem item = new CartItem(cart.getId(), productId, 1);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.of(item));
        when(items.findByCartId(cart.getId())).thenReturn(List.of(item));

        CartResponse response = service.updateItemQuantity(cart.getId(), productId, new UpdateCartItemQuantityRequest(9));

        assertEquals(9, item.getQuantity());
        assertEquals(9, response.items().get(0).quantity());
    }

    @Test
    void updateItemQuantityThrowsWhenCartMissing() {
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateItemQuantity(cartId, productId, new UpdateCartItemQuantityRequest(2)));
    }

    @Test
    void updateItemQuantityThrowsWhenItemMissing() {
        Cart cart = new Cart(userId);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateItemQuantity(cart.getId(), productId, new UpdateCartItemQuantityRequest(2)));
    }

    @Test
    void removeItemSucceeds() {
        Cart cart = new Cart(userId);
        CartItem item = new CartItem(cart.getId(), productId, 1);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.of(item));
        when(items.findByCartId(cart.getId())).thenReturn(List.of());

        CartResponse response = service.removeItem(cart.getId(), productId);

        verify(items).delete(item);
        assertTrue(response.items().isEmpty());
    }

    @Test
    void removeItemThrowsWhenCartMissing() {
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.removeItem(cartId, productId));
    }

    @Test
    void removeItemThrowsWhenItemMissing() {
        Cart cart = new Cart(userId);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartIdAndProductId(cart.getId(), productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.removeItem(cart.getId(), productId));
    }

    @Test
    void clearCartSucceeds() {
        Cart cart = new Cart(userId);
        when(carts.findById(cart.getId())).thenReturn(Optional.of(cart));
        when(items.findByCartId(cart.getId())).thenReturn(List.of());

        CartResponse response = service.clearCart(cart.getId());

        verify(items).deleteByCartId(cart.getId());
        assertTrue(response.items().isEmpty());
    }

    @Test
    void clearCartThrowsWhenCartMissing() {
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.clearCart(cartId));
    }
}
