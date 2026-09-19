package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogue changes to related-product recommendations, end to end: products created and activated
 * through product-service are recommended in the documented deterministic order through the API
 * gateway, and deactivating or deleting a candidate eventually removes it.
 *
 * <p>Each step crosses the product outbox, {@code product.events.v1} and the recommendation consumer
 * asynchronously, so every assertion polls with a bounded timeout.
 *
 * <p>Opt-in: {@code -De2e.kafka=true -De2e.recommendation=true}, with product, recommendation and the
 * gateway on the Kafka overlay.
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
@EnabledIfSystemProperty(named = "e2e.recommendation", matches = "true")
class ProductRecommendationE2ETest extends E2ETestBase {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

    private String token;
    private UUID phones;
    private UUID laptops;
    private UUID acme;

    @Test
    void recommendationsFollowTheDocumentedOrderAndReactToCatalogueChanges() throws Exception {
        token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        phones = uuid(send("POST", serviceUrls.product(), "/api/v1/categories", 201, json.createObjectNode()
                .put("name", "Rec Phones " + token).put("slug", "rec-phones-" + token)), "categoryId");
        laptops = uuid(send("POST", serviceUrls.product(), "/api/v1/categories", 201, json.createObjectNode()
                .put("name", "Rec Laptops " + token).put("slug", "rec-laptops-" + token)), "categoryId");
        acme = uuid(send("POST", serviceUrls.product(), "/api/v1/brands", 201, json.createObjectNode()
                .put("name", "Rec Acme " + token).put("slug", "rec-acme-" + token)), "brandId");

        UUID source = activeProduct("source", phones, acme, "100.00");
        UUID categoryAndBrand = activeProduct("category-brand", phones, acme, "110.00");   // 5 + 2 + 1 = 8
        UUID categoryOnly = activeProduct("category-only", phones, null, "400.00");        // 5
        UUID brandOnly = activeProduct("brand-only", laptops, acme, "105.00");             // 2 + 1 = 3
        UUID unrelated = activeProduct("unrelated", laptops, null, "100.00");              // not related

        JsonNode page = awaitRecommendations(source, List.of(categoryAndBrand, categoryOnly, brandOnly));
        JsonNode best = page.path("items").get(0);
        assertEquals(8, best.path("score").asInt());
        assertEquals("SAME_CATEGORY", best.path("reasons").get(0).asText());
        assertEquals("SAME_BRAND", best.path("reasons").get(1).asText());
        assertEquals("SIMILAR_PRICE", best.path("reasons").get(2).asText());
        assertEquals(5, page.path("items").get(1).path("score").asInt());
        assertEquals(3, page.path("items").get(2).path("score").asInt());
        assertTrue(page.path("items").findValuesAsText("productId").stream()
                .noneMatch(id -> id.equals(source.toString()) || id.equals(unrelated.toString())));

        send("PUT", serviceUrls.product(), "/api/v1/products/" + categoryAndBrand, 200,
                update("category-brand", phones, acme, "110.00", "INACTIVE", false));
        awaitRecommendations(source, List.of(categoryOnly, brandOnly));

        send("DELETE", serviceUrls.product(), "/api/v1/products/" + categoryOnly, 204, null);
        awaitRecommendations(source, List.of(brandOnly));
    }

    private UUID activeProduct(String label, UUID category, UUID brand, String price) throws Exception {
        ObjectNode create = json.createObjectNode()
                .put("sku", "REC-" + label + "-" + token).put("name", "Rec " + label + " " + token)
                .put("slug", "rec-" + label + "-" + token).put("categoryId", category.toString())
                .put("price", price).put("currency", "USD");
        if (brand != null) {
            create.put("brandId", brand.toString());
        }
        UUID id = uuid(send("POST", serviceUrls.product(), "/api/v1/products", 201, create), "productId");
        send("PUT", serviceUrls.product(), "/api/v1/products/" + id, 200, update(label, category, brand, price, "ACTIVE", true));
        return id;
    }

    private ObjectNode update(String label, UUID category, UUID brand, String price, String status, boolean active) {
        ObjectNode body = json.createObjectNode()
                .put("name", "Rec " + label + " " + token).put("slug", "rec-" + label + "-" + token)
                .put("categoryId", category.toString()).put("price", price).put("currency", "USD")
                .put("status", status).put("active", active);
        if (brand != null) {
            body.put("brandId", brand.toString());
        }
        return body;
    }

    /** Polls the gateway until the recommendation ids equal {@code expected}, in order. */
    private JsonNode awaitRecommendations(UUID source, List<UUID> expected) throws Exception {
        Instant deadline = Instant.now().plus(EVENT_TIMEOUT);
        String last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get(serviceUrls.gateway(), "/api/v1/recommendations/products/" + source + "?limit=10");
            last = response.statusCode() + " " + response.body();
            if (response.statusCode() == 200) {
                JsonNode data = json.readTree(response.body()).path("data");
                List<String> ids = new ArrayList<>(data.path("items").findValuesAsText("productId"));
                if (ids.equals(expected.stream().map(UUID::toString).toList())) {
                    return data;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return Assertions.fail("Timed out after " + EVENT_TIMEOUT + " waiting for recommendations " + expected
                + "; last response: " + last + ". Are product, recommendation and the gateway on the Kafka overlay?");
    }

    private HttpResponse<String> get(String baseUrl, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl.replaceFirst("/+$", "") + path))
                .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode send(String method, String baseUrl, String path, int expectedStatus, ObjectNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl.replaceFirst("/+$", "") + path))
                .timeout(REQUEST_TIMEOUT);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), method + " " + path + " response: " + response.body());
        return response.body() == null || response.body().isBlank() ? null : json.readTree(response.body()).path("data");
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }
}
