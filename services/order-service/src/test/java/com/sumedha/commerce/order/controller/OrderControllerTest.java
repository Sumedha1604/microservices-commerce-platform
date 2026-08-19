package com.sumedha.commerce.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderItemResponse;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.exception.GlobalExceptionHandler;
import com.sumedha.commerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    MockMvc mvc;
    OrderService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(OrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CreateOrderItemRequest itemRequest() {
        return new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 2);
    }

    private CreateOrderRequest createRequest() {
        return new CreateOrderRequest(userId, "USD", List.of(itemRequest()));
    }

    private OrderResponse response(OrderStatus status) {
        Instant now = Instant.now();
        OrderItemResponse item = new OrderItemResponse(
                UUID.randomUUID(), productId, "Widget", "SKU-1", new BigDecimal("10.00"), 2, new BigDecimal("20.00"), now);
        return new OrderResponse(orderId, userId, status, new BigDecimal("20.00"), new BigDecimal("20.00"),
                "USD", List.of(item), now, now);
    }

    // ---------- CREATE ----------

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        when(service.create(any())).thenReturn(response(OrderStatus.PENDING));

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(service).create(any());
    }

    @Test
    void createRejectsNullUserId() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(null, "USD", List.of(itemRequest()));

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsInvalidCurrency() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(userId, "US", List.of(itemRequest()));

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsEmptyItems() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(userId, "USD", List.of());

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsInvalidNestedItem() throws Exception {
        CreateOrderItemRequest invalidItem =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 0);
        CreateOrderRequest request = new CreateOrderRequest(userId, "USD", List.of(invalidItem));

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- GET BY ID ----------

    @Test
    void getByIdReturnsOrder() throws Exception {
        when(service.getById(orderId)).thenReturn(response(OrderStatus.PENDING));

        mvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId.toString()));
    }

    @Test
    void getByIdMapsResourceNotFound() throws Exception {
        when(service.getById(orderId)).thenThrow(new ResourceNotFoundException("Order not found"));

        mvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void getByIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/orders/{orderId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- GET BY USER ----------

    @Test
    void getByUserIdReturnsList() throws Exception {
        when(service.getByUserId(userId)).thenReturn(List.of(response(OrderStatus.PENDING)));

        mvc.perform(get("/api/v1/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(orderId.toString()));
    }

    @Test
    void getByUserIdReturnsEmptyListWhenNoneFound() throws Exception {
        when(service.getByUserId(userId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getByUserIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/orders/user/{userId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- CONFIRM ----------

    @Test
    void confirmTransitionsPendingToConfirmed() throws Exception {
        when(service.confirm(orderId)).thenReturn(response(OrderStatus.CONFIRMED));

        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        verify(service).confirm(orderId);
    }

    @Test
    void confirmMapsResourceNotFound() throws Exception {
        when(service.confirm(orderId)).thenThrow(new ResourceNotFoundException("Order not found"));

        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void confirmMapsInvalidTransitionToConflict() throws Exception {
        when(service.confirm(orderId)).thenThrow(new ConflictException("Only pending orders can be confirmed"));

        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Only pending orders can be confirmed"));
    }

    @Test
    void confirmWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/orders/{orderId}/confirm", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- CANCEL ----------

    @Test
    void cancelReturnsCancelledOrder() throws Exception {
        when(service.cancel(orderId)).thenReturn(response(OrderStatus.CANCELLED));

        mvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(service).cancel(orderId);
    }

    @Test
    void cancelMapsResourceNotFound() throws Exception {
        when(service.cancel(orderId)).thenThrow(new ResourceNotFoundException("Order not found"));

        mvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void cancelMapsAlreadyCancelledToConflict() throws Exception {
        when(service.cancel(orderId)).thenThrow(new ConflictException("Order is already cancelled"));

        mvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Order is already cancelled"));
    }

    @Test
    void cancelWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/orders/{orderId}/cancel", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- Optimistic locking / unexpected errors ----------

    @Test
    void confirmMapsOptimisticLockingFailureToConflict() throws Exception {
        when(service.confirm(orderId)).thenThrow(new ObjectOptimisticLockingFailureException(Object.class, orderId.toString()));

        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Order was modified concurrently. Please retry."));
    }

    @Test
    void createMapsUnexpectedErrorToSanitizedResponse() throws Exception {
        when(service.create(any())).thenThrow(new RuntimeException("db connection string leaked here"));

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createRequest())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
