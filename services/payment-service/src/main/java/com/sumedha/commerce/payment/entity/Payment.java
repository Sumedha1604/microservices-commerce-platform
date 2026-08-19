package com.sumedha.commerce.payment.entity;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.payment.enums.PaymentStatus;
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
@Table(name = "payments")
public class Payment {

    private static final int FAILURE_REASON_MAX_LENGTH = 500;

    @Id
    @Column(name = "payment_id")
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Payment() {
    }

    public Payment(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        id = UUID.randomUUID();
        this.orderId = orderId;
        this.userId = userId;
        status = PaymentStatus.PENDING;
        this.amount = amount;
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

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getUserId() {
        return userId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
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
     * Only PENDING payments can be authorized.
     */
    public void authorize(String provider, String providerReference) {
        if (status != PaymentStatus.PENDING) {
            throw new ConflictException("Only pending payments can be authorized");
        }
        status = PaymentStatus.AUTHORIZED;
        this.provider = provider;
        this.providerReference = providerReference;
    }

    /**
     * Only AUTHORIZED payments can be captured.
     */
    public void capture() {
        if (status != PaymentStatus.AUTHORIZED) {
            throw new ConflictException("Only authorized payments can be captured");
        }
        status = PaymentStatus.CAPTURED;
    }

    /**
     * PENDING and AUTHORIZED payments can be failed.
     */
    public void fail(String reason) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.AUTHORIZED) {
            throw new ConflictException("Only pending or authorized payments can be failed");
        }
        status = PaymentStatus.FAILED;
        failureReason = sanitize(reason);
    }

    /**
     * PENDING and AUTHORIZED payments can be cancelled.
     */
    public void cancel() {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.AUTHORIZED) {
            throw new ConflictException("Only pending or authorized payments can be cancelled");
        }
        status = PaymentStatus.CANCELLED;
    }

    /**
     * Only CAPTURED payments can be refunded.
     */
    public void refund() {
        if (status != PaymentStatus.CAPTURED) {
            throw new ConflictException("Only captured payments can be refunded");
        }
        status = PaymentStatus.REFUNDED;
    }

    private static String sanitize(String reason) {
        String trimmed = reason.trim();
        return trimmed.length() > FAILURE_REASON_MAX_LENGTH
                ? trimmed.substring(0, FAILURE_REASON_MAX_LENGTH)
                : trimmed;
    }
}
