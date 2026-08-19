package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.inventory.InventoryGetResponse;
import com.sumedha.commerce.checkout.dto.downstream.inventory.ReserveReleaseInventoryRequest;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryClientTest {

    @Test
    void usesCurrentGetReserveAndReleaseEndpoints() throws Exception {
        UUID inventoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String response = inventoryResponse(inventoryId, productId);
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(200, response);
            server.respond(200, response);
            server.respond(200, response);
            InventoryClient client = new InventoryClient(server.baseUrl());

            InventoryGetResponse inventory = client.getInventoryByProductId(productId);
            assertEquals("GET", server.lastRequest().method());
            assertEquals("/api/v1/inventory/product/" + productId, server.lastRequest().path());
            assertEquals(5, inventory.availableQuantity());

            client.reserve(inventoryId, new ReserveReleaseInventoryRequest(2));
            assertEquals("POST", server.lastRequest().method());
            assertEquals("/api/v1/inventory/" + inventoryId + "/reserve", server.lastRequest().path());
            assertEquals("{\"quantity\":2}", server.lastRequest().body());

            client.release(inventoryId, new ReserveReleaseInventoryRequest(2));
            assertEquals("POST", server.lastRequest().method());
            assertEquals("/api/v1/inventory/" + inventoryId + "/release", server.lastRequest().path());
            assertEquals("{\"quantity\":2}", server.lastRequest().body());
        }
    }

    @Test
    void translatesServerFailure() throws Exception {
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(500, "{}");

            assertThrows(InternalServerException.class,
                    () -> new InventoryClient(server.baseUrl()).getInventoryByProductId(UUID.randomUUID()));
        }
    }

    private static String inventoryResponse(UUID inventoryId, UUID productId) {
        return "{\"success\":true,\"message\":\"Success\",\"data\":{\"id\":\"" + inventoryId
                + "\",\"productId\":\"" + productId + "\",\"quantity\":7,\"reservedQuantity\":2,\"availableQuantity\":5,\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"},\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    }
}
