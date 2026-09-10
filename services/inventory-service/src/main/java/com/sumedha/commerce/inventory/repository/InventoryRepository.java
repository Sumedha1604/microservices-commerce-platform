package com.sumedha.commerce.inventory.repository;

import com.sumedha.commerce.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    boolean existsByProductId(UUID productId);

    /**
     * Loads the row with a {@code SELECT ... FOR UPDATE} lock held until the transaction ends.
     *
     * <p>Used by compensation, where the check ("is this much actually reserved?") and the
     * decrement must not be separated by another writer. The optimistic {@code @Version} would
     * also catch such a race, but only by failing one side, and a compensation that loses that
     * race repeatedly ends up on the dead-letter topic with its stock still held.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") UUID productId);
}
