package com.sumedha.commerce.cart.service;

import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.entity.Cart;
import com.sumedha.commerce.cart.entity.CartItem;
import com.sumedha.commerce.cart.mapper.CartMapper;
import com.sumedha.commerce.cart.repository.CartItemRepository;
import com.sumedha.commerce.cart.repository.CartRepository;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CartServiceImpl implements CartService {

    private final CartRepository carts;
    private final CartItemRepository items;

    public CartServiceImpl(CartRepository carts, CartItemRepository items) {
        this.carts = carts;
        this.items = items;
    }

    @Transactional
    public CartResponse createCart(CreateCartRequest request) {
        if (carts.existsByUserId(request.userId())) {
            throw new ConflictException("Cart already exists for user");
        }
        Cart cart;
        try {
            cart = carts.saveAndFlush(new Cart(request.userId()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Cart already exists for user");
        }
        return CartMapper.toResponse(cart, items.findByCartId(cart.getId()));
    }

    @Transactional(readOnly = true)
    public CartResponse getCartById(UUID cartId) {
        Cart cart = cart(cartId);
        return CartMapper.toResponse(cart, items.findByCartId(cart.getId()));
    }

    @Transactional(readOnly = true)
    public CartResponse getCartByUserId(UUID userId) {
        Cart cart = carts.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
        return CartMapper.toResponse(cart, items.findByCartId(cart.getId()));
    }

    @Transactional
    public CartResponse addItem(UUID cartId, AddCartItemRequest request) {
        Cart cart = cart(cartId);
        items.findByCartIdAndProductId(cartId, request.productId())
                .ifPresentOrElse(
                        existing -> existing.increaseQuantity(request.quantity()),
                        () -> items.save(new CartItem(cartId, request.productId(), request.quantity())));
        return CartMapper.toResponse(cart, items.findByCartId(cartId));
    }

    @Transactional
    public CartResponse updateItemQuantity(UUID cartId, UUID productId, UpdateCartItemQuantityRequest request) {
        Cart cart = cart(cartId);
        item(cartId, productId).setQuantity(request.quantity());
        return CartMapper.toResponse(cart, items.findByCartId(cartId));
    }

    @Transactional
    public CartResponse removeItem(UUID cartId, UUID productId) {
        Cart cart = cart(cartId);
        items.delete(item(cartId, productId));
        return CartMapper.toResponse(cart, items.findByCartId(cartId));
    }

    @Transactional
    public CartResponse clearCart(UUID cartId) {
        Cart cart = cart(cartId);
        items.deleteByCartId(cartId);
        return CartMapper.toResponse(cart, items.findByCartId(cartId));
    }

    private Cart cart(UUID id) {
        return carts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
    }

    private CartItem item(UUID cartId, UUID productId) {
        return items.findByCartIdAndProductId(cartId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
    }
}
