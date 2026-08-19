package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CheckoutHappyPathE2ETest extends E2ETestBase {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int QUANTITY = 3;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("19.99");
    private static final BigDecimal EXPECTED_TOTAL = new BigDecimal("59.97");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @Test
    void createsPendingOrderAndPaymentWhileReservingInventory() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        UUID userId = UUID.randomUUID();

        UUID categoryId = uuid(post(serviceUrls.product(), "/api/v1/categories", categoryRequest(unique), 201), "categoryId");
        JsonNode product = post(serviceUrls.product(), "/api/v1/products", productRequest(unique, categoryId), 201);
        UUID productId = uuid(product, "productId");
        put(serviceUrls.product(), "/api/v1/products/" + productId, activeProductRequest(unique, categoryId), 200);

        JsonNode initialInventory = post(serviceUrls.inventory(), "/api/v1/inventory", inventoryRequest(productId), 201);
        int initiallyReserved = initialInventory.path("reservedQuantity").asInt();

        UUID cartId = uuid(post(serviceUrls.cart(), "/api/v1/carts", cartRequest(userId), 201), "id");
        post(serviceUrls.cart(), "/api/v1/carts/" + cartId + "/items", cartItemRequest(productId), 200);

        JsonNode checkout = post(serviceUrls.checkout(), "/api/v1/checkouts", checkoutRequest(cartId), 201);
        UUID orderId = uuid(checkout, "orderId");
        UUID paymentId = uuid(checkout, "paymentId");

        assertEquals(cartId, uuid(checkout, "cartId"));
        assertEquals("PENDING", checkout.path("orderStatus").asText());
        assertEquals("PENDING", checkout.path("paymentStatus").asText());
        assertMoney(EXPECTED_TOTAL, checkout, "total");
        assertEquals("USD", checkout.path("currency").asText());

        JsonNode reservedInventory = get(serviceUrls.inventory(), "/api/v1/inventory/product/" + productId, 200);
        assertEquals(initiallyReserved + QUANTITY, reservedInventory.path("reservedQuantity").asInt());

        JsonNode order = get(serviceUrls.order(), "/api/v1/orders/" + orderId, 200);
        assertEquals(orderId, uuid(order, "id"));
        assertEquals(userId, uuid(order, "userId"));
        assertEquals("PENDING", order.path("status").asText());
        assertMoney(EXPECTED_TOTAL, order, "total");
        JsonNode orderItems = order.path("items");
        assertEquals(1, orderItems.size());
        JsonNode orderItem = orderItems.get(0);
        assertEquals(productId, uuid(orderItem, "productId"));
        assertEquals("E2E Product " + unique, orderItem.path("productName").asText());
        assertEquals("e2e-sku-" + unique, orderItem.path("sku").asText());
        assertEquals(QUANTITY, orderItem.path("quantity").asInt());
        assertMoney(UNIT_PRICE, orderItem, "unitPrice");
        assertMoney(EXPECTED_TOTAL, orderItem, "lineTotal");

        JsonNode payment = get(serviceUrls.payment(), "/api/v1/payments/order/" + orderId, 200);
        assertEquals(paymentId, uuid(payment, "id"));
        assertEquals(orderId, uuid(payment, "orderId"));
        assertEquals(userId, uuid(payment, "userId"));
        assertEquals("PENDING", payment.path("status").asText());
        assertMoney(EXPECTED_TOTAL, payment, "amount");
        assertEquals("USD", payment.path("currency").asText());
    }

    private ObjectNode categoryRequest(String unique) {
        return objectMapper.createObjectNode()
                .put("name", "E2E Category " + unique)
                .put("slug", "e2e-category-" + unique)
                .put("description", "Checkout E2E test category");
    }

    private ObjectNode productRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("sku", "e2e-sku-" + unique)
                .put("name", "E2E Product " + unique)
                .put("slug", "e2e-product-" + unique)
                .put("categoryId", categoryId.toString())
                .put("price", UNIT_PRICE)
                .put("currency", "USD");
    }

    private ObjectNode activeProductRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("name", "E2E Product " + unique)
                .put("slug", "e2e-product-" + unique)
                .put("shortDescription", "Checkout E2E test product")
                .put("description", "Checkout E2E test product")
                .put("categoryId", categoryId.toString())
                .put("price", UNIT_PRICE)
                .put("currency", "USD")
                .put("status", "ACTIVE")
                .put("active", true);
    }

    private ObjectNode inventoryRequest(UUID productId) {
        return objectMapper.createObjectNode().put("productId", productId.toString()).put("quantity", 10);
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
        return send("POST", baseUrl, path, body, expectedStatus);
    }

    private JsonNode put(String baseUrl, String path, ObjectNode body, int expectedStatus) throws Exception {
        return send("PUT", baseUrl, path, body, expectedStatus);
    }

    private JsonNode get(String baseUrl, String path, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path)).GET().timeout(REQUEST_TIMEOUT).build();
        return responseData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), expectedStatus, "GET", path);
    }

    private JsonNode send(String method, String baseUrl, String path, ObjectNode body, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return responseData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), expectedStatus, method, path);
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

    private void assertMoney(BigDecimal expected, JsonNode node, String field) {
        assertEquals(0, expected.compareTo(node.path(field).decimalValue()), "Unexpected " + field);
    }

    private URI uri(String baseUrl, String path) {
        return URI.create(baseUrl.replaceFirst("/+$", "") + path);
    }
}
