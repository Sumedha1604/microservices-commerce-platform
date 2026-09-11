package com.sumedha.commerce.product.entity;

import com.sumedha.commerce.product.enums.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One product lifecycle event owed to Kafka, written in the same transaction as the product change.
 *
 * <p>{@code eventId} and {@code payload} are fixed when the row is created and never change: every
 * publish attempt - including a republish after a crash between the broker's acknowledgement and
 * the {@code PUBLISHED} update - sends identical bytes, which is what lets consumers deduplicate.
 */
@Entity
@Table(name = "product_outbox_event")
public class ProductOutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

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

    @Transient
    private boolean isNew = true;

    protected ProductOutboxEvent() {
    }

    public ProductOutboxEvent(UUID aggregateId, UUID eventId, String eventType, int schemaVersion,
                              String topic, String eventKey, String payload, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.aggregateType = "Product";
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

    public void markAttemptFailed(String error, Instant nextAttempt) {
        attemptCount++;
        lastError = error;
        nextAttemptAt = nextAttempt;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

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
