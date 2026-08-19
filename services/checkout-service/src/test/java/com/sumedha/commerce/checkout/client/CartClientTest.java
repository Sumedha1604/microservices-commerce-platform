package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.cart.CartGetResponse;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CartClientTest {

    @Test
    void getsCartFromCurrentEndpointAndMapsResponse() throws Exception {
        UUID cartId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(200, "{\"success\":true,\"message\":\"Success\",\"data\":{\"id\":\"" + cartId
                    + "\",\"userId\":\"" + userId + "\",\"items\":[{\"id\":\"" + itemId
                    + "\",\"productId\":\"" + productId + "\",\"quantity\":2,\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}]"
                    + ",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"},\"timestamp\":\"2026-01-01T00:00:00Z\"}");

            CartGetResponse cart = new CartClient(server.baseUrl()).getCart(cartId);

            assertEquals("GET", server.lastRequest().method());
            assertEquals("/api/v1/carts/" + cartId, server.lastRequest().path());
            assertEquals(userId, cart.userId());
            assertEquals(productId, cart.items().getFirst().productId());
            assertEquals(2, cart.items().getFirst().quantity());
        }
    }

    @Test
    void translatesNotFoundResponse() throws Exception {
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(404, "{}");

            assertThrows(ResourceNotFoundException.class, () -> new CartClient(server.baseUrl()).getCart(UUID.randomUUID()));
        }
    }
}
