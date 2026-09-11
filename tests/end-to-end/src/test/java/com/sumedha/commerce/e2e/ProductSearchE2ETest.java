package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue-to-search projection end to end: a product created, activated, renamed and deleted
 * through product-service becomes findable, reflects the rename, and disappears - as seen through the
 * API gateway's {@code /api/v1/search/**} route, never by asking product-service.
 *
 * <p>Every search assertion polls with a bounded timeout, because each step crosses the product outbox,
 * {@code product.events.v1} and the search consumer asynchronously.
 *
 * <p>Opt-in twice over: it needs the Kafka overlay ({@code -De2e.kafka=true}) and product, search and the
 * gateway on it ({@code -De2e.search=true}).
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
@EnabledIfSystemProperty(named = "e2e.search", matches = "true")
class ProductSearchE2ETest extends E2ETestBase {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

    @Test
    void aCatalogueProductBecomesSearchableReflectsUpdatesAndDisappearsWhenDeleted() throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String renamedToken = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        UUID categoryId = uuid(send("POST", serviceUrls.product(), "/api/v1/categories", 201, json.createObjectNode()
                .put("name", "Search E2E " + token).put("slug", "search-e2e-" + token)), "categoryId");
        ObjectNode create = json.createObjectNode()
                .put("sku", "SEARCH-" + token).put("name", "Searchable Widget " + token)
                .put("slug", "searchable-widget-" + token).put("categoryId", categoryId.toString())
                .put("price", "42.50").put("currency", "USD");
        UUID productId = uuid(send("POST", serviceUrls.product(), "/api/v1/products", 201, create), "productId");

        // New products are DRAFT: indexed, but not visible to default search until activated.
        send("PUT", serviceUrls.product(), "/api/v1/products/" + productId, 200,
                update(token, categoryId, "Searchable Widget " + token, "A widget worth finding", "ACTIVE"));

        JsonNode found = awaitSearch(token, page -> page.path("totalElements").asLong() == 1,
                "the activated product to be searchable");
        JsonNode hit = found.path("items").get(0);
        assertEquals(productId.toString(), hit.path("productId").asText());
        assertEquals("Searchable Widget " + token, hit.path("name").asText());
        assertEquals("SEARCH-" + token, hit.path("sku").asText());
        assertEquals("ACTIVE", hit.path("status").asText());
        assertEquals(0, new java.math.BigDecimal("42.50").compareTo(hit.path("price").decimalValue()));

        send("PUT", serviceUrls.product(), "/api/v1/products/" + productId, 200,
                update(token, categoryId, "Renamed Gadget " + renamedToken, "Now described differently", "ACTIVE"));

        JsonNode renamed = awaitSearch(renamedToken, page -> page.path("totalElements").asLong() == 1,
                "search to reflect the rename");
        assertEquals("Renamed Gadget " + renamedToken, renamed.path("items").get(0).path("name").asText());
        assertTrue(searchPage("widget worth finding").path("items").findValuesAsText("productId").stream()
                .noneMatch(productId.toString()::equals), "the old description is no longer indexed");

        send("DELETE", serviceUrls.product(), "/api/v1/products/" + productId, 204, null);

        awaitSearch(renamedToken, page -> page.path("totalElements").asLong() == 0, "the deleted product to disappear");
        awaitSearch("SEARCH-" + token, page -> page.path("totalElements").asLong() == 0, "its SKU to stop matching too");
    }

    private ObjectNode update(String token, UUID categoryId, String name, String description, String status) {
        return json.createObjectNode()
                .put("name", name).put("slug", "searchable-widget-" + token)
                .put("description", description).put("categoryId", categoryId.toString())
                .put("price", "42.50").put("currency", "USD").put("status", status).put("active", true);
    }

    private JsonNode awaitSearch(String q, Predicate<JsonNode> condition, String what) throws Exception {
        Instant deadline = Instant.now().plus(EVENT_TIMEOUT);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = searchPage(q);
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return Assertions.fail("Timed out after " + EVENT_TIMEOUT + " waiting for " + what + "; last page: " + last
                + ". Are product, search and the gateway on the Kafka overlay?");
    }

    /** Always through the API gateway. */
    private JsonNode searchPage(String q) throws Exception {
        return send("GET", serviceUrls.gateway(),
                "/api/v1/search/products?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), 200, null);
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
        if (response.body() == null || response.body().isBlank()) {
            return null;
        }
        JsonNode envelope = json.readTree(response.body());
        assertTrue(envelope.path("success").asBoolean(), response.body());
        return envelope.path("data");
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }
}
