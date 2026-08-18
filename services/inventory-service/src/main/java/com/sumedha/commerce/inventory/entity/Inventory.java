package com.sumedha.commerce.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "inventory_id")
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Inventory() {
    }

    public Inventory(UUID productId, int quantity) {
        id = UUID.randomUUID();
        this.productId = productId;
        this.quantity = quantity;
        this.reservedQuantity = 0;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Transient
    public int getAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    public void update(int quantity, int reservedQuantity) {
        this.quantity = quantity;
        this.reservedQuantity = reservedQuantity;
    }

    public void reserve(int amount) {
        reservedQuantity += amount;
    }

    public void release(int amount) {
        reservedQuantity -= amount;
    }
}
