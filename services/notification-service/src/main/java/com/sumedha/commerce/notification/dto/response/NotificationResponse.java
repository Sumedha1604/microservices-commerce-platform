package com.sumedha.commerce.notification.dto.response;

import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of one notification record.
 *
 * <p>{@code status} is {@code CREATED} and {@code channel} is {@code INTERNAL} for every record
 * today: recorded, not sent. {@code occurredAt} is when the source payment event was raised;
 * {@code createdAt} is when this service recorded the notification.
 */
public record NotificationResponse(
        UUID notificationId,
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID paymentId,
        UUID userId,
        NotificationChannel channel,
        NotificationType notificationType,
        String subject,
        String message,
        NotificationStatus status,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
