package com.sumedha.commerce.notification.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.service.NotificationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only inspection of notification records.
 *
 * <p>There is intentionally no create/send endpoint: notifications are raised only by payment
 * events, so no caller can fabricate one.
 *
 * <p><strong>Authorization is not enforced here</strong>, like every other service API on the
 * platform today: auth-service issues tokens but no service or gateway route validates them yet.
 * These records name users by id and describe their payments, so this must be closed (a gateway
 * auth filter, or a resource-server check scoping reads to the caller's own {@code userId}) before
 * production. See {@code docs/api/notification-service.md}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationQueryService service;

    public NotificationController(NotificationQueryService service) {
        this.service = service;
    }

    @GetMapping("/{notificationId}")
    public ApiResponse<NotificationResponse> getById(@PathVariable("notificationId") UUID notificationId) {
        return ApiResponse.success(service.getById(notificationId));
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @RequestParam(name = "orderId", required = false) UUID orderId,
            @RequestParam(name = "userId", required = false) UUID userId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "notificationType", required = false) NotificationType notificationType,
            @RequestParam(name = "status", required = false) NotificationStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        return ApiResponse.success(service.list(orderId, userId, eventType, notificationType, status, page, size));
    }

    @GetMapping("/order/{orderId}")
    public ApiResponse<PageResponse<NotificationResponse>> listByOrder(
            @PathVariable("orderId") UUID orderId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        return ApiResponse.success(service.listByOrder(orderId, page, size));
    }
}
