package com.sumedha.commerce.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable marker that one compensation event has already been applied.
 *
 * <p>Kafka delivery is at-least-once and the offset commit is not atomic with the database
 * transaction, so the same {@code eventId} can arrive again - after a rebalance, after an outbox
 * republish, or after an operator replays it. Writing this row in the SAME transaction as the
 * stock change is what makes that safe: the primary key rejects the second attempt, and a failed
 * release rolls the marker back with it.
 *
 * <p>This is the only idempotency mechanism available here, because there are no reservation
 * records whose status could serve as the guard instead.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** The order the compensation was for; kept for operator lookups, not for logic. */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType, UUID orderId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.orderId = orderId;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public UUID getOrderId() { return orderId; }
    public Instant getProcessedAt() { return processedAt; }
}
