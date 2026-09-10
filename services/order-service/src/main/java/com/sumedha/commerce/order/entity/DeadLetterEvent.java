package com.sumedha.commerce.order.entity;

import com.sumedha.commerce.order.enums.DeadLetterStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable, inspectable record of one record that reached {@code payment.events.v1.DLT}.
 *
 * <p>This is operator infrastructure, not part of the order aggregate: no foreign key to
 * {@code orders}, and nothing here ever drives an order transition. The envelope fields
 * ({@code eventId}, {@code eventType}, {@code schemaVersion}, {@code orderId}) are a best-effort
 * parse of the stored payload and are all nullable - a malformed record must still be
 * inspectable, which is most of the point.
 *
 * <p>{@code payload} is the original record value, byte-for-byte as the consumer saw it. Replay
 * republishes exactly this string under exactly {@code eventKey}, so the {@code eventId} inside
 * it is preserved and consumer-side deduplication stays authoritative.
 */
@Entity
@Table(name = "dead_letter_event")
public class DeadLetterEvent {

    @Id
    private UUID id;

    /** Parsed from the payload envelope; null when the payload could not be parsed. */
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "schema_version")
    private Integer schemaVersion;

    /** The aggregate the event refers to, parsed from the payload. */
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "original_topic", nullable = false, length = 255)
    private String originalTopic;

    @Column(name = "original_partition")
    private Integer originalPartition;

    @Column(name = "original_offset")
    private Long originalOffset;

    @Column(name = "dlt_topic", nullable = false, length = 255)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private long dltOffset;

    @Column(name = "dlt_timestamp", nullable = false)
    private Instant dltTimestamp;

    @Column(name = "event_key", length = 255)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /**
     * The failing exception's class name as a plain string - the cause where the recoverer
     * recorded one, otherwise the wrapper - copied from the DLT headers purely as diagnostic
     * text. It is never resolved to a Java type, so the contract does not depend on it.
     */
    @Column(name = "exception_class", columnDefinition = "text")
    private String exceptionClass;

    @Column(name = "exception_message", columnDefinition = "text")
    private String exceptionMessage;

    @Column(name = "consumer_group", length = 255)
    private String consumerGroup;

    @Column(length = 255)
    private String traceparent;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeadLetterStatus status;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "last_replay_error", columnDefinition = "text")
    private String lastReplayError;

    protected DeadLetterEvent() {
    }

    private DeadLetterEvent(Builder builder) {
        this.id = UUID.randomUUID();
        this.eventId = builder.eventId;
        this.eventType = builder.eventType;
        this.schemaVersion = builder.schemaVersion;
        this.orderId = builder.orderId;
        this.originalTopic = builder.originalTopic;
        this.originalPartition = builder.originalPartition;
        this.originalOffset = builder.originalOffset;
        this.dltTopic = builder.dltTopic;
        this.dltPartition = builder.dltPartition;
        this.dltOffset = builder.dltOffset;
        this.dltTimestamp = builder.dltTimestamp;
        this.eventKey = builder.eventKey;
        this.payload = builder.payload;
        this.exceptionClass = builder.exceptionClass;
        this.exceptionMessage = builder.exceptionMessage;
        this.consumerGroup = builder.consumerGroup;
        this.traceparent = builder.traceparent;
        this.firstSeenAt = builder.firstSeenAt;
        this.status = DeadLetterStatus.NEW;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Records an acknowledged replay. Payload, key and eventId are never touched. */
    public void markReplayed(Instant now) {
        status = DeadLetterStatus.REPLAYED;
        replayedAt = now;
        replayCount++;
        lastReplayError = null;
    }

    /** Records a failed replay attempt. The record stays replayable. */
    public void markReplayFailed(String error) {
        status = DeadLetterStatus.REPLAY_FAILED;
        replayCount++;
        lastReplayError = error;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public UUID getOrderId() { return orderId; }
    public String getOriginalTopic() { return originalTopic; }
    public Integer getOriginalPartition() { return originalPartition; }
    public Long getOriginalOffset() { return originalOffset; }
    public String getDltTopic() { return dltTopic; }
    public int getDltPartition() { return dltPartition; }
    public long getDltOffset() { return dltOffset; }
    public Instant getDltTimestamp() { return dltTimestamp; }
    public String getEventKey() { return eventKey; }
    public String getPayload() { return payload; }
    public String getExceptionClass() { return exceptionClass; }
    public String getExceptionMessage() { return exceptionMessage; }
    public String getConsumerGroup() { return consumerGroup; }
    public String getTraceparent() { return traceparent; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public DeadLetterStatus getStatus() { return status; }
    public Instant getReplayedAt() { return replayedAt; }
    public int getReplayCount() { return replayCount; }
    public String getLastReplayError() { return lastReplayError; }

    public static final class Builder {
        private UUID eventId;
        private String eventType;
        private Integer schemaVersion;
        private UUID orderId;
        private String originalTopic;
        private Integer originalPartition;
        private Long originalOffset;
        private String dltTopic;
        private int dltPartition;
        private long dltOffset;
        private Instant dltTimestamp;
        private String eventKey;
        private String payload;
        private String exceptionClass;
        private String exceptionMessage;
        private String consumerGroup;
        private String traceparent;
        private Instant firstSeenAt;

        public Builder eventId(UUID v) { this.eventId = v; return this; }
        public Builder eventType(String v) { this.eventType = v; return this; }
        public Builder schemaVersion(Integer v) { this.schemaVersion = v; return this; }
        public Builder orderId(UUID v) { this.orderId = v; return this; }
        public Builder originalTopic(String v) { this.originalTopic = v; return this; }
        public Builder originalPartition(Integer v) { this.originalPartition = v; return this; }
        public Builder originalOffset(Long v) { this.originalOffset = v; return this; }
        public Builder dltTopic(String v) { this.dltTopic = v; return this; }
        public Builder dltPartition(int v) { this.dltPartition = v; return this; }
        public Builder dltOffset(long v) { this.dltOffset = v; return this; }
        public Builder dltTimestamp(Instant v) { this.dltTimestamp = v; return this; }
        public Builder eventKey(String v) { this.eventKey = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder exceptionClass(String v) { this.exceptionClass = v; return this; }
        public Builder exceptionMessage(String v) { this.exceptionMessage = v; return this; }
        public Builder consumerGroup(String v) { this.consumerGroup = v; return this; }
        public Builder traceparent(String v) { this.traceparent = v; return this; }
        public Builder firstSeenAt(Instant v) { this.firstSeenAt = v; return this; }

        public DeadLetterEvent build() {
            return new DeadLetterEvent(this);
        }
    }
}
