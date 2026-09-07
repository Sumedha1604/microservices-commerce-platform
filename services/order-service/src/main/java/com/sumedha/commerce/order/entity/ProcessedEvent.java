package com.sumedha.commerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable marker that one inbound Kafka event has already been applied.
 *
 * <p>Kafka delivery is at-least-once and the offset commit is not atomic with the database
 * transaction, so the same {@code eventId} can be redelivered. Writing this row in the SAME
 * transaction as the order transition is what makes reprocessing safe: the primary key rejects
 * the second attempt, and a failed transition rolls the marker back with it.
 *
 * <p>Deliberately not foreign-keyed to {@code orders} - it is consumer infrastructure, not part
 * of the order aggregate.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

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
        processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
