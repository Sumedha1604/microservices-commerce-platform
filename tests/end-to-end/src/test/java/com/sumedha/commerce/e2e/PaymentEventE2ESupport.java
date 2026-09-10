package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sumedha.commerce.e2e.config.ServiceUrls;
import org.junit.jupiter.api.Assertions;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checkout fixture and HTTP plumbing shared by the two Kafka-dependent E2E tests.
 *
 * <p>Both start from a real checkout - the same category/product/inventory/cart chain the
 * synchronous {@code CheckoutHappyPathE2ETest} builds - so the order and payment under test are
 * the ones checkout itself created, not hand-made rows. Where those tests stop (order and payment
 * both PENDING), these continue: the payment is authorized or failed, and the resulting order
 * transition arrives only after the event has crossed {@code payment.events.v1}.
 *
 * <p>That transition is asynchronous, so the terminal status is polled with a bounded timeout
 * rather than asserted immediately.
 */
final class PaymentEventE2ESupport {

    /** Ample for a local single-node broker; the consumer normally applies the event in well under a second. */
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final BigDecimal UNIT_PRICE = new BigDecimal("19.99");
    /** Units in the cart, and therefore the units checkout reserves. */
    static final int QUANTITY = 3;
    /** Units the inventory row is created with. */
    static final int STOCKED_QUANTITY = 10;

    /** UNIT_PRICE x QUANTITY, as checkout computes it. */
    static final BigDecimal EXPECTED_TOTAL = new BigDecimal("59.97");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final ServiceUrls urls;

    PaymentEventE2ESupport(ServiceUrls urls) {
        this.urls = urls;
    }

    /** The order and payment a real checkout produced, both PENDING, and the product it reserved. */
    record Checkout(UUID orderId, UUID paymentId, UUID userId, UUID productId) {
    }

    /**
     * Drives the full synchronous checkout chain and asserts its usual outcome: an order and a
     * payment that are both PENDING, which is the precondition every payment event acts on.
     */
    Checkout checkout() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        UUID userId = UUID.randomUUID();

        UUID categoryId = uuid(post(urls.product(), "/api/v1/categories", categoryRequest(unique), 201), "categoryId");
        UUID productId = uuid(post(urls.product(), "/api/v1/products", productRequest(unique, categoryId), 201), "productId");
        put(urls.product(), "/api/v1/products/" + productId, activeProductRequest(unique, categoryId), 200);
        post(urls.inventory(), "/api/v1/inventory", inventoryRequest(productId), 201);

        UUID cartId = uuid(post(urls.cart(), "/api/v1/carts", cartRequest(userId), 201), "id");
        post(urls.cart(), "/api/v1/carts/" + cartId + "/items", cartItemRequest(productId), 200);

        JsonNode checkout = post(urls.checkout(), "/api/v1/checkouts", checkoutRequest(cartId), 201);
        assertEquals("PENDING", checkout.path("orderStatus").asText());
        assertEquals("PENDING", checkout.path("paymentStatus").asText());

        return new Checkout(uuid(checkout, "orderId"), uuid(checkout, "paymentId"), userId, productId);
    }

    JsonNode inventoryForProduct(UUID productId) throws Exception {
        return get(urls.inventory(), "/api/v1/inventory/product/" + productId);
    }

    /**
     * Polls the product's reserved quantity until it reaches {@code expected}. A release is two
     * asynchronous hops behind the payment call - payment outbox to order-service, then order
     * outbox to inventory-service - so it is never asserted immediately.
     */
    JsonNode awaitReservedQuantity(UUID productId, int expected) throws Exception {
        Instant deadline = Instant.now().plus(EVENT_TIMEOUT);
        int lastSeen = -1;
        while (Instant.now().isBefore(deadline)) {
            JsonNode inventory = inventoryForProduct(productId);
            lastSeen = inventory.path("reservedQuantity").asInt();
            if (lastSeen == expected) {
                return inventory;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return Assertions.fail("Product " + productId + " reserved quantity never reached " + expected + " within "
                + EVENT_TIMEOUT + "; last seen " + lastSeen + ". Is inventory-service on the Kafka overlay?");
    }

    /**
     * Asserts the reserved quantity stays at {@code expected} for the whole window. Used to prove a
     * negative - that nothing released the stock - so it has to watch, not glance.
     */
    void assertReservedQuantityHolds(UUID productId, int expected, Duration window) throws Exception {
        Instant until = Instant.now().plus(window);
        while (Instant.now().isBefore(until)) {
            assertEquals(expected, inventoryForProduct(productId).path("reservedQuantity").asInt(),
                    "reserved quantity of product " + productId + " changed");
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    JsonNode authorizePayment(UUID paymentId) throws Exception {
        ObjectNode request = objectMapper.createObjectNode()
                .put("provider", "stripe")
                .put("providerReference", "e2e-" + UUID.randomUUID());
        return post(urls.payment(), "/api/v1/payments/" + paymentId + "/authorize", request, 200);
    }

    JsonNode failPayment(UUID paymentId, String reason) throws Exception {
        ObjectNode request = objectMapper.createObjectNode().put("reason", reason);
        return post(urls.payment(), "/api/v1/payments/" + paymentId + "/fail", request, 200);
    }

    String orderStatus(UUID orderId) throws Exception {
        return order(orderId).path("status").asText();
    }

    JsonNode order(UUID orderId) throws Exception {
        return get(urls.order(), "/api/v1/orders/" + orderId);
    }

    /**
     * Polls the order until it reaches {@code expectedStatus}, failing with the last status seen
     * if the bounded timeout expires first.
     */
    JsonNode awaitOrderStatus(UUID orderId, String expectedStatus) throws Exception {
        Instant deadline = Instant.now().plus(EVENT_TIMEOUT);
        String lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode order = order(orderId);
            lastSeen = order.path("status").asText();
            if (expectedStatus.equals(lastSeen)) {
                return order;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return Assertions.fail("Order " + orderId + " never reached " + expectedStatus + " within " + EVENT_TIMEOUT
                + "; last status was " + lastSeen + ". Is the Kafka overlay running?");
    }

    /** The page of notifications notification-service has recorded for one order. */
    JsonNode notificationsForOrder(UUID orderId) throws Exception {
        return get(urls.notification(), "/api/v1/notifications/order/" + orderId);
    }

    /**
     * Polls notification-service until the order has exactly {@code expectedCount} notifications and
     * returns them. The record is written by a second, independent consumer of the payment event, so
     * it is never asserted immediately.
     */
    JsonNode awaitNotificationsForOrder(UUID orderId, int expectedCount) throws Exception {
        Instant deadline = Instant.now().plus(EVENT_TIMEOUT);
        long lastSeen = -1;
        while (Instant.now().isBefore(deadline)) {
            JsonNode page = notificationsForOrder(orderId);
            lastSeen = page.path("totalElements").asLong();
            if (lastSeen == expectedCount) {
                return page.path("items");
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return Assertions.fail("Order " + orderId + " never had " + expectedCount + " notification(s) within "
                + EVENT_TIMEOUT + "; last seen " + lastSeen + ". Is notification-service on the Kafka overlay?");
    }

    /** Asserts the order's notification count stays at {@code expected} for the whole window. */
    void assertNotificationCountHolds(UUID orderId, int expected, Duration window) throws Exception {
        Instant until = Instant.now().plus(window);
        while (Instant.now().isBefore(until)) {
            assertEquals(expected, notificationsForOrder(orderId).path("totalElements").asInt(),
                    "notification count of order " + orderId + " changed");
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private ObjectNode categoryRequest(String unique) {
        return objectMapper.createObjectNode()
                .put("name", "Kafka E2E Category " + unique)
                .put("slug", "kafka-e2e-category-" + unique)
                .put("description", "Payment event E2E test category");
    }

    private ObjectNode productRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("sku", "kafka-e2e-sku-" + unique)
                .put("name", "Kafka E2E Product " + unique)
                .put("slug", "kafka-e2e-product-" + unique)
                .put("categoryId", categoryId.toString())
                .put("price", UNIT_PRICE)
                .put("currency", "USD");
    }

    private ObjectNode activeProductRequest(String unique, UUID categoryId) {
        return objectMapper.createObjectNode()
                .put("name", "Kafka E2E Product " + unique)
                .put("slug", "kafka-e2e-product-" + unique)
                .put("shortDescription", "Payment event E2E test product")
                .put("description", "Payment event E2E test product")
                .put("categoryId", categoryId.toString())
                .put("price", UNIT_PRICE)
                .put("currency", "USD")
                .put("status", "ACTIVE")
                .put("active", true);
    }

    private ObjectNode inventoryRequest(UUID productId) {
        return objectMapper.createObjectNode()
                .put("productId", productId.toString())
                .put("quantity", STOCKED_QUANTITY);
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

    private JsonNode get(String baseUrl, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path)).GET().timeout(REQUEST_TIMEOUT).build();
        return responseData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), 200, "GET", path);
    }

    private JsonNode send(String method, String baseUrl, String path, ObjectNode body, int expectedStatus)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return responseData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), expectedStatus, method, path);
    }

    private JsonNode responseData(HttpResponse<String> response, int expectedStatus, String method, String path)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode(), method + " " + path + " response: " + response.body());
        JsonNode envelope = objectMapper.readTree(response.body());
        assertEquals(true, envelope.path("success").asBoolean());
        JsonNode data = envelope.path("data");
        assertFalse(data.isMissingNode() || data.isNull());
        return data;
    }

    UUID uuid(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        assertNotNull(value, "Missing " + field);
        return UUID.fromString(value);
    }

    void assertMoney(BigDecimal expected, JsonNode node, String field) {
        assertEquals(0, expected.compareTo(node.path(field).decimalValue()), "Unexpected " + field);
    }

    private URI uri(String baseUrl, String path) {
        return URI.create(baseUrl.replaceFirst("/+$", "") + path);
    }
}
