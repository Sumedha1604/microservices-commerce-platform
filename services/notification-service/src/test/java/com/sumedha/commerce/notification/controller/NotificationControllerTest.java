package com.sumedha.commerce.notification.controller;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.exception.GlobalExceptionHandler;
import com.sumedha.commerce.notification.service.NotificationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private static final String BASE = "/api/v1/notifications";

    MockMvc mvc;
    NotificationQueryService service;

    final UUID notificationId = UUID.randomUUID();
    final UUID eventId = UUID.randomUUID();
    final UUID orderId = UUID.randomUUID();
    final UUID paymentId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(NotificationQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private NotificationResponse response() {
        Instant now = Instant.parse("2026-09-11T10:15:31Z");
        return new NotificationResponse(notificationId, eventId, "PaymentFailed", orderId, paymentId, userId,
                NotificationChannel.INTERNAL, NotificationType.PAYMENT_FAILED,
                "Payment failed for order " + orderId, "Your payment could not be completed.",
                NotificationStatus.CREATED, Instant.parse("2026-09-11T10:15:30Z"), now, now);
    }

    // ---------- detail ----------

    @Test
    void detailReturnsTheNotificationInTheSharedEnvelope() throws Exception {
        when(service.getById(notificationId)).thenReturn(response());

        mvc.perform(get(BASE + "/" + notificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.data.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.data.eventType").value("PaymentFailed"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.userId").value(userId.toString()))
                .andExpect(jsonPath("$.data.channel").value("INTERNAL"))
                .andExpect(jsonPath("$.data.notificationType").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.subject").value("Payment failed for order " + orderId));
    }

    @Test
    void anUnknownNotificationIsNotFound() throws Exception {
        when(service.getById(notificationId))
                .thenThrow(new ResourceNotFoundException("Notification not found: " + notificationId));

        mvc.perform(get(BASE + "/" + notificationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Notification not found: " + notificationId));
    }

    @Test
    void aMalformedNotificationIdIsABadRequest() throws Exception {
        mvc.perform(get(BASE + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verifyNoInteractions(service);
    }

    // ---------- list ----------

    @Test
    void listPassesEveryFilterAndReturnsThePage() throws Exception {
        when(service.list(eq(orderId), eq(userId), eq("PaymentFailed"), eq(NotificationType.PAYMENT_FAILED),
                eq(NotificationStatus.CREATED), eq(1), eq(10)))
                .thenReturn(PageResponse.of(List.of(response()), 1, 10, 25));

        mvc.perform(get(BASE)
                        .param("orderId", orderId.toString())
                        .param("userId", userId.toString())
                        .param("eventType", "PaymentFailed")
                        .param("notificationType", "PAYMENT_FAILED")
                        .param("status", "CREATED")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(25))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
    }

    @Test
    void listUsesBoundedDefaultsWhenNoPagingIsGiven() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalPages").value(0));

        verify(service).list(null, null, null, null, null, 0, 20);
    }

    @Test
    void anOversizedPageIsABadRequest() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new BadRequestException("size must be between 1 and 100"));

        mvc.perform(get(BASE).param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("size must be between 1 and 100"));
    }

    @Test
    void anUnknownNotificationTypeOrStatusIsABadRequest() throws Exception {
        mvc.perform(get(BASE).param("notificationType", "ORDER_SHIPPED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'notificationType'"));
        mvc.perform(get(BASE).param("status", "SENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'status'"));
        mvc.perform(get(BASE).param("page", "first"))
                .andExpect(status().isBadRequest());

        verify(service, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aMalformedOrderIdFilterIsABadRequest() throws Exception {
        mvc.perform(get(BASE).param("orderId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'orderId'"));

        verifyNoInteractions(service);
    }

    // ---------- by order ----------

    @Test
    void listByOrderReturnsThatOrdersNotifications() throws Exception {
        when(service.listByOrder(orderId, 0, 5)).thenReturn(PageResponse.of(List.of(response()), 0, 5, 1));

        mvc.perform(get(BASE + "/order/" + orderId).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void listByOrderWithAMalformedOrderIdIsABadRequest() throws Exception {
        mvc.perform(get(BASE + "/order/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verifyNoInteractions(service);
    }

    // ---------- read-only ----------

    @Test
    void thereIsNoWayToCreateOrSendANotification() throws Exception {
        mvc.perform(post(BASE).contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
        mvc.perform(delete(BASE + "/" + notificationId))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    void anUnexpectedErrorIsSanitized() throws Exception {
        when(service.getById(notificationId)).thenThrow(new RuntimeException("jdbc:postgresql://secret"));

        mvc.perform(get(BASE + "/" + notificationId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
