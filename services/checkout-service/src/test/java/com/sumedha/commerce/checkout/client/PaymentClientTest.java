package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.payment.CreatePaymentRequest;
import com.sumedha.commerce.checkout.dto.downstream.payment.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentClientTest {

    @Test
    void createsPaymentAtCurrentEndpointAndMapsResponse() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(201, paymentResponse(paymentId, orderId, userId));
            CreatePaymentRequest request = new CreatePaymentRequest(orderId, userId, new BigDecimal("25.00"), "USD");

            PaymentResponse payment = new PaymentClient(server.baseUrl()).createPayment(request);

            assertEquals("POST", server.lastRequest().method());
            assertEquals("/api/v1/payments", server.lastRequest().path());
            assertEquals(true, server.lastRequest().body().contains("\"orderId\":\"" + orderId + "\""));
            assertEquals(true, server.lastRequest().body().contains("\"amount\":25.00"));
            assertEquals(paymentId, payment.id());
            assertEquals("AUTHORIZED", payment.status());
        }
    }

    private static String paymentResponse(UUID paymentId, UUID orderId, UUID userId) {
        return "{\"success\":true,\"message\":\"Success\",\"data\":{\"id\":\"" + paymentId
                + "\",\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\",\"status\":\"AUTHORIZED\",\"amount\":25.00,\"currency\":\"USD\",\"provider\":\"provider\",\"providerReference\":\"reference\",\"failureReason\":null,\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"},\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    }
}
