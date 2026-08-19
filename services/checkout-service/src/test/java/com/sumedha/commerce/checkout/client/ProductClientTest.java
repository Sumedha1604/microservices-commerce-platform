package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.product.ProductGetResponse;
import com.sumedha.commerce.common.core.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductClientTest {

    @Test
    void getsProductFromCurrentEndpointAndMapsOnlyRequiredFields() throws Exception {
        UUID productId = UUID.randomUUID();
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(200, "{\"success\":true,\"message\":\"Success\",\"data\":{\"productId\":\"" + productId
                    + "\",\"sku\":\"SKU-1\",\"name\":\"Product\",\"price\":12.50,\"currency\":\"USD\",\"status\":\"ACTIVE\",\"active\":true},\"timestamp\":\"2026-01-01T00:00:00Z\"}");

            ProductGetResponse product = new ProductClient(server.baseUrl()).getProduct(productId);

            assertEquals("GET", server.lastRequest().method());
            assertEquals("/api/v1/products/" + productId, server.lastRequest().path());
            assertEquals("SKU-1", product.sku());
            assertEquals("ACTIVE", product.status());
            assertEquals(true, product.active());
        }
    }

    @Test
    void translatesConflictResponse() throws Exception {
        try (DownstreamClientTestServer server = new DownstreamClientTestServer()) {
            server.respond(409, "{}");

            assertThrows(ConflictException.class, () -> new ProductClient(server.baseUrl()).getProduct(UUID.randomUUID()));
        }
    }
}
