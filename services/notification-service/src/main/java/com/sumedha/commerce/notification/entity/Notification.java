package com.sumedha.commerce.notification.entity;

import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable notification raised from one payment event.
 *
 * <p>The record <em>is</em> the delivery boundary for now: {@link NotificationStatus#CREATED} on
 * the {@link NotificationChannel#INTERNAL} channel means recorded, not sent. No contact details
 * are stored because none are available - the recipient is the {@code userId} carried by the
 * event, a UUID reference into user data this service does not own.
 *
 * <p>Implements {@link Persistable} so a new record is always {@code persist}ed, never
 * {@code merge}d: the id is assigned here rather than by the database, and without this Spring
 * Data would issue a SELECT first and could turn an insert into an update.
 */
@Entity
@Table(name = "notification")
public class Notification implements Persistable<UUID> {

    @Id
    @Column(name = "notification_id")
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    /** When the source event was raised, as stated by its envelope. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected Notification() {
    }

    public Notification(UUID eventId, String eventType, Instant occurredAt,
                        UUID orderId, UUID paymentId, UUID userId,
                        NotificationChannel channel, NotificationType notificationType,
                        String subject, String message) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.userId = userId;
        this.channel = channel;
        this.notificationType = notificationType;
        this.subject = subject;
        this.message = message;
        this.status = NotificationStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getUserId() { return userId; }
    public NotificationChannel getChannel() { return channel; }
    public NotificationType getNotificationType() { return notificationType; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public NotificationStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
