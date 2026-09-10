package com.sumedha.commerce.order.entity;

import com.sumedha.commerce.order.enums.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One event order-service owes Kafka, written in the same transaction as the state change that
 * produced it.
 *
 * <p>{@code eventId} is generated once here and never regenerated. Every republish - including
 * one after a crash between the broker's acknowledgement and the {@code PUBLISHED} update -
 * carries that same id, which is exactly what lets the consumer deduplicate instead of applying
 * the compensation twice.
 *
 * <p>{@code payload} is the fully serialized envelope, frozen at write time. The publisher never
 * re-renders it, so a later code change cannot alter an event that was already promised.
 */
@Entity
@Table(name = "order_outbox_event")
public class OrderOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** The order this event is about; also the per-aggregate ordering key. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(nullable = false, length = 255)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected OrderOutboxEvent() {
    }

    public OrderOutboxEvent(UUID aggregateId, UUID eventId, String eventType, int schemaVersion,
                            String topic, String eventKey, String payload, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.aggregateType = "Order";
        this.aggregateId = aggregateId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant now) {
        status = OutboxEventStatus.PUBLISHED;
        publishedAt = now;
        lastError = null;
        nextAttemptAt = null;
    }

    /** Records a failed send. The row stays PENDING and keeps its eventId and payload. */
    public void markAttemptFailed(String error, Instant nextAttempt) {
        attemptCount++;
        lastError = error;
        nextAttemptAt = nextAttempt;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getTopic() { return topic; }
    public String getEventKey() { return eventKey; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
