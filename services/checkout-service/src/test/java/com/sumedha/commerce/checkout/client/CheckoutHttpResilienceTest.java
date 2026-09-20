package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.inventory.ReserveReleaseInventoryRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderRequest;
import com.sumedha.commerce.checkout.dto.downstream.payment.CreatePaymentRequest;
import com.sumedha.commerce.checkout.exception.DownstreamBadGatewayException;
import com.sumedha.commerce.checkout.exception.DownstreamTimeoutException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "checkout.http.connect-timeout=100ms",
        "checkout.http.read-timeout=100ms",
        "resilience4j.circuitbreaker.configs.checkout-default.wait-duration-in-open-state=100ms"
})
class CheckoutHttpResilienceTest {

    private static final DownstreamClientTestServer server = createServer();

    @Autowired ProductClient productClient;
    @Autowired InventoryClient inventoryClient;
    @Autowired OrderClient orderClient;
    @Autowired PaymentClient paymentClient;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @Autowired MeterRegistry meters;

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("checkout.services.cart-url", server::baseUrl);
        registry.add("checkout.services.product-url", server::baseUrl);
        registry.add("checkout.services.inventory-url", server::baseUrl);
        registry.add("checkout.services.order-url", server::baseUrl);
        registry.add("checkout.services.payment-url", server::baseUrl);
    }

    @BeforeEach
    void resetBreakers() {
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }

    @Test
    void transientReadFailureRetriesExactlyOnceThenSucceeds() {
        UUID productId = UUID.randomUUID();
        int before = server.requestCount();
        server.respond(503, "{}");
        server.respond(200, productResponse(productId));

        assertEquals(productId, productClient.getProduct(productId).productId());
        assertEquals(2, server.requestCount() - before);
    }

    @Test
    void businessFourHundredIsNotRetried() {
        int before = server.requestCount();
        server.respond(404, "{}");

        assertThrows(ResourceNotFoundException.class, () -> productClient.getProduct(UUID.randomUUID()));
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void mutationFailureIsNeverBlindlyRetried() {
        int before = server.requestCount();
        server.respond(503, "{}");

        assertThrows(DownstreamBadGatewayException.class, () -> inventoryClient.reserve(
                UUID.randomUUID(), new ReserveReleaseInventoryRequest(1)));
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void inventoryMutationTimeoutIsSingleAttemptAndControlled() {
        int before = server.requestCount();
        server.respondAfter(500, 200, "{}");

        assertThrows(DownstreamTimeoutException.class, () -> inventoryClient.reserve(
                UUID.randomUUID(), new ReserveReleaseInventoryRequest(1)));
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void orderMutationTimeoutIsSingleAttemptAndControlled() {
        int before = server.requestCount();
        server.respondAfter(500, 200, "{}");

        assertThrows(DownstreamTimeoutException.class, () -> orderClient.createOrder(
                new CreateOrderRequest(UUID.randomUUID(), "USD", List.of())));
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void paymentMutationTimeoutIsSingleAttemptAndControlled() {
        int before = server.requestCount();
        server.respondAfter(500, 200, "{}");

        assertThrows(DownstreamTimeoutException.class, () -> paymentClient.createPayment(
                new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "USD")));
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void openCircuitFailsFastAndRecoveryAllowsProbe() {
        var breaker = circuitBreakers.circuitBreaker("productService");
        breaker.transitionToOpenState();
        int before = server.requestCount();

        assertThrows(CallNotPermittedException.class, () -> productClient.getProduct(UUID.randomUUID()));
        assertEquals(0, server.requestCount() - before);

        breaker.transitionToHalfOpenState();
        UUID productId = UUID.randomUUID();
        server.respond(200, productResponse(productId));
        assertEquals(productId, productClient.getProduct(productId).productId());
        assertEquals(1, server.requestCount() - before);
    }

    @Test
    void circuitBreakerMetricsArePublished() {
        server.respond(503, "{}");
        server.respond(200, productResponse(UUID.randomUUID()));
        productClient.getProduct(UUID.randomUUID());
        assertNotNull(meters.find("resilience4j.circuitbreaker.calls").meter());
        assertNotNull(meters.find("resilience4j.retry.calls").meter());
    }

    private static String productResponse(UUID productId) {
        return "{\"success\":true,\"message\":\"Success\",\"data\":{\"productId\":\"" + productId
                + "\",\"sku\":\"SKU-1\",\"name\":\"Product\",\"price\":12.50,\"currency\":\"USD\","
                + "\"status\":\"ACTIVE\",\"active\":true},\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    }

    private static DownstreamClientTestServer createServer() {
        try {
            return new DownstreamClientTestServer();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
