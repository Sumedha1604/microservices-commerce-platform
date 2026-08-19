package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderItemRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.OrderResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderClientTest {

    @Test
    void usesCurrentCreateAndCancelEndpointsWithMappedOrderResponse() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String response = orderResponse(orderId, userId);
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(201, response);
            server.respond(200, response);
            OrderClient client = new OrderClient(server.baseUrl());
            CreateOrderRequest request = new CreateOrderRequest(userId, "USD", List.of(
                    new CreateOrderItemRequest(productId, "Product", "SKU-1", new BigDecimal("12.50"), 2)));

            OrderResponse order = client.createOrder(request);
            assertEquals("POST", server.lastRequest().method());
            assertEquals("/api/v1/orders", server.lastRequest().path());
            assertEquals(true, server.lastRequest().body().contains("\"productId\":\"" + productId + "\""));
            assertEquals(true, server.lastRequest().body().contains("\"unitPrice\":12.50"));
            assertEquals(orderId, order.id());
            assertEquals(new BigDecimal("25.00"), order.total());

            client.cancelOrder(orderId);
            assertEquals("POST", server.lastRequest().method());
            assertEquals("/api/v1/orders/" + orderId + "/cancel", server.lastRequest().path());
        }
    }

    private static String orderResponse(UUID orderId, UUID userId) {
        return "{\"success\":true,\"message\":\"Success\",\"data\":{\"id\":\"" + orderId
                + "\",\"userId\":\"" + userId + "\",\"status\":\"PENDING\",\"subtotal\":25.00,\"total\":25.00,\"currency\":\"USD\",\"items\":[],\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"},\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    }
}
