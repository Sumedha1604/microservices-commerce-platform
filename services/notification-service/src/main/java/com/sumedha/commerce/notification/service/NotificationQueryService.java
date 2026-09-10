package com.sumedha.commerce.notification.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.mapper.NotificationMapper;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only access to notification records. Nothing in this service creates, sends or changes a
 * notification - records are written only by the payment-event consumer.
 */
@Service
public class NotificationQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> SUPPORTED_EVENT_TYPES =
            Set.of(EventTypes.PAYMENT_AUTHORIZED, EventTypes.PAYMENT_FAILED);

    private final NotificationRepository notifications;

    public NotificationQueryService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public NotificationResponse getById(UUID notificationId) {
        return notifications.findById(notificationId)
                .map(NotificationMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID orderId, UUID userId, String eventType,
                                                   NotificationType notificationType,
                                                   NotificationStatus status, int page, int size) {
        requireValidPage(page, size);
        String normalizedEventType = eventType == null || eventType.isBlank() ? null : eventType;
        if (normalizedEventType != null && !SUPPORTED_EVENT_TYPES.contains(normalizedEventType)) {
            throw new BadRequestException("eventType must be one of " + EventTypes.PAYMENT_AUTHORIZED
                    + ", " + EventTypes.PAYMENT_FAILED);
        }

        // Newest first, with the id as a stable tie-breaker so pages cannot repeat or skip rows.
        Page<Notification> found = notifications.search(orderId, userId, normalizedEventType,
                notificationType, status,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));

        List<NotificationResponse> items = found.getContent().stream()
                .map(NotificationMapper::toResponse)
                .toList();
        return PageResponse.of(items, page, size, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listByOrder(UUID orderId, int page, int size) {
        return list(orderId, null, null, null, null, page, size);
    }

    private static void requireValidPage(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
