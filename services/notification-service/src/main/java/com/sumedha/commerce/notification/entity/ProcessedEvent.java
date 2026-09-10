package com.sumedha.commerce.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable marker that one payment event has already produced its notifications.
 *
 * <p>Kafka delivery is at-least-once and the offset commit is not atomic with the database
 * transaction, so the same {@code eventId} can arrive again. The marker is claimed in the SAME
 * transaction as the notification insert (see
 * {@link com.sumedha.commerce.notification.repository.ProcessedEventRepository#insertIfAbsent}),
 * so a redelivery finds it taken and a failed insert rolls it back.
 *
 * <p>Rows are written only through that guarded insert; this mapping exists for reads.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** The order the event was about; kept for operator lookups, not for logic. */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public UUID getOrderId() { return orderId; }
    public Instant getProcessedAt() { return processedAt; }
}
