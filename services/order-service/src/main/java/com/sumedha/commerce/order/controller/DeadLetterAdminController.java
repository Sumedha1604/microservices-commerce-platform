package com.sumedha.commerce.order.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventDetailResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterReplayResponse;
import com.sumedha.commerce.order.enums.DeadLetterStatus;
import com.sumedha.commerce.order.service.DeadLetterAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator endpoints for the payment-event dead-letter topic.
 *
 * <p><strong>Authorization is not enforced here.</strong> This repository has no cross-service
 * authorization model yet - auth-service issues tokens but no service validates a role - so
 * inventing one for this controller alone would be a fake boundary. These routes therefore live
 * under {@code /api/v1/admin/**} as a namespace an edge policy can match on, and hardening them
 * (gateway-level admin role, network policy, or a resource-server filter here) is required before
 * production. See {@code docs/events/dlt-operations.md}.
 *
 * <p>Note the shape of the replay route: the client names a stored record, never a topic and
 * never a payload. There is no path by which a caller can choose what gets published or where.
 */
@RestController
@RequestMapping("/api/v1/admin/dlt/payment-events")
public class DeadLetterAdminController {

    private final DeadLetterAdminService service;

    public DeadLetterAdminController(DeadLetterAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<DeadLetterEventResponse>> list(
            @RequestParam(name = "eventId", required = false) UUID eventId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "orderId", required = false) UUID orderId,
            @RequestParam(name = "partition", required = false) Integer partition,
            @RequestParam(name = "status", required = false) DeadLetterStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        return ApiResponse.success(service.list(eventId, eventType, orderId, partition, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeadLetterEventDetailResponse> getById(@PathVariable("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @PostMapping("/{id}/replay")
    public ApiResponse<DeadLetterReplayResponse> replay(@PathVariable("id") UUID id) {
        return ApiResponse.success("Dead-letter record republished to its original topic",
                service.replay(id));
    }
}
