package com.sumedha.commerce.notification.mapper;

import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.entity.Notification;

public final class NotificationMapper {

    private NotificationMapper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventId(),
                notification.getEventType(),
                notification.getOrderId(),
                notification.getPaymentId(),
                notification.getUserId(),
                notification.getChannel(),
                notification.getNotificationType(),
                notification.getSubject(),
                notification.getMessage(),
                notification.getStatus(),
                notification.getOccurredAt(),
                notification.getCreatedAt(),
                notification.getUpdatedAt());
    }
}
