package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CheckoutOrderFailureE2ETest extends E2ETestBase {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int QUANTITY = 100;
    // Fits Product's NUMERIC(19,4), but its rounded checkout total exceeds Order's NUMERIC(19,2).
    private static final String PRODUCT_PRICE = "999999999999999.9999";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

    @Test
    void releasesInventoryAndDoesNotCreatePaymentWhenOrderPersistenceFails() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        UUID userId = UUID.randomUUID();
        UUID categoryId = uuid(post(serviceUrls.product(), "/api/v1/categories", categoryRequest(unique), 201), "categoryId");
        UUID productId = createActiveProduct(unique, categoryId);
        JsonNode initialInventory = post(serviceUrls.inventory(), "/api/v1/inventory", inventoryRequest(productId), 201);

        UUID cartId = uuid(post(serviceUrls.cart(), "/api/v1/carts", cartRequest(userId), 201), "id");
        post(serviceUrls.cart(), "/api/v1/carts/" + cartId + "/items", cartItemRequest(productId), 200);

        HttpResponse<String> checkoutFailure = send("POST", serviceUrls.checkout(), "/api/v1/checkouts", checkoutRequest(cartId));
        assertEquals(500, checkoutFailure.statusCode(), "Checkout response: " + checkoutFailure.body());

        JsonNode inventoryAfterFailure = get(serviceUrls.inventory(), "/api/v1/inventory/product/" + productId, 200);
        assertEquals(initialInventory.path("reservedQuantity").asInt(), inventoryAfterFailure.path("reservedQuantity").asInt());

        JsonNode payments = get(serviceUrls.payment(), "/api/v1/payments/user/" + userId, 200);
        assertEquals(0, payments.size());
    }

    private UUID createActiveProduct(String unique, UUID categoryId) throws Exception {
        JsonNode product = post(serviceUrls.product(), "/api/v1/products", productRequest(unique, categoryId), 201);
        UUID productId = uuid(product, "productId");
        put(serviceUrls.product(), "/api/v1/products/" + productId, activeProductRequest(unique, categoryId), 200);
        return productId;
    }

    private ObjectNode categoryRequest(String unique) {
        return objectMapper.createObjectNode()
                .put("name", "E2E Order Failure Category " + unique)
                .put("slug", "e2e-order-failure-category-" + unique)
                .put("description", "Checkout order-failure E2E test category");
    }

    private ObjectNode productRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("sku", "e2e-order-failure-sku-" + unique)
                .put("name", "E2E Order Failure Product " + unique)
                .put("slug", "e2e-order-failure-product-" + unique)
                .put("categoryId", categoryId.toString())
                .put("price", PRODUCT_PRICE)
                .put("currency", "USD");
    }

    private ObjectNode activeProductRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("name", "E2E Order Failure Product " + unique)
                .put("slug", "e2e-order-failure-product-" + unique)
                .put("shortDescription", "Checkout order-failure E2E test product")
                .put("description", "Checkout order-failure E2E test product")
                .put("categoryId", categoryId.toString())
                .put("price", PRODUCT_PRICE)
                .put("currency", "USD")
                .put("status", "ACTIVE")
                .put("active", true);
    }

    private ObjectNode inventoryRequest(UUID productId) {
        return objectMapper.createObjectNode().put("productId", productId.toString()).put("quantity", QUANTITY);
    }

    private ObjectNode cartRequest(UUID userId) {
        return objectMapper.createObjectNode().put("userId", userId.toString());
    }

    private ObjectNode cartItemRequest(UUID productId) {
        return objectMapper.createObjectNode().put("productId", productId.toString()).put("quantity", QUANTITY);
    }

    private ObjectNode checkoutRequest(UUID cartId) {
        return objectMapper.createObjectNode().put("cartId", cartId.toString());
    }

    private JsonNode post(String baseUrl, String path, ObjectNode body, int expectedStatus) throws Exception {
        return responseData(send("POST", baseUrl, path, body), expectedStatus, "POST", path);
    }

    private JsonNode put(String baseUrl, String path, ObjectNode body, int expectedStatus) throws Exception {
        return responseData(send("PUT", baseUrl, path, body), expectedStatus, "PUT", path);
    }

    private JsonNode get(String baseUrl, String path, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path)).GET().timeout(REQUEST_TIMEOUT).build();
        return responseData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), expectedStatus, "GET", path);
    }

    private HttpResponse<String> send(String method, String baseUrl, String path, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode responseData(HttpResponse<String> response, int expectedStatus, String method, String path) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), method + " " + path + " response: " + response.body());
        JsonNode envelope = objectMapper.readTree(response.body());
        assertEquals(true, envelope.path("success").asBoolean());
        JsonNode data = envelope.path("data");
        assertFalse(data.isMissingNode() || data.isNull());
        return data;
    }

    private UUID uuid(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        assertNotNull(value, "Missing " + field);
        return UUID.fromString(value);
    }

    private URI uri(String baseUrl, String path) {
        return URI.create(baseUrl.replaceFirst("/+$", "") + path);
    }
}
