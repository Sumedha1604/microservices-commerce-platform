package com.sumedha.commerce.cart.repository;

import com.sumedha.commerce.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);

    boolean existsByCartIdAndProductId(UUID cartId, UUID productId);

    void deleteByCartId(UUID cartId);
}
