package com.sumedha.commerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores an immutable product/pricing snapshot at order time.
 * Does not reference the Product entity - product data may change later.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @Column(name = "order_item_id")
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column
    private String sku;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "created_at")
    private Instant createdAt;

    protected OrderItem() {
    }

    public OrderItem(UUID orderId, UUID productId, String productName, String sku,
                      BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
        id = UUID.randomUUID();
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.sku = sku;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = lineTotal;
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
