package com.sumedha.commerce.order.entity;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.order.enums.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "order_id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Order() {
    }

    public Order(UUID userId, BigDecimal subtotal, BigDecimal total, String currency) {
        id = UUID.randomUUID();
        this.userId = userId;
        status = OrderStatus.PENDING;
        this.subtotal = subtotal;
        this.total = total;
        this.currency = currency;
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

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
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

    /**
     * Only PENDING orders can be confirmed.
     */
    public void confirm() {
        if (status != OrderStatus.PENDING) {
            throw new ConflictException("Only pending orders can be confirmed");
        }
        status = OrderStatus.CONFIRMED;
    }

    /**
     * PENDING and CONFIRMED orders can be cancelled; CANCELLED is terminal.
     */
    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            throw new ConflictException("Order is already cancelled");
        }
        status = OrderStatus.CANCELLED;
    }
}
