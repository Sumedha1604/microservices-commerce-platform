package com.sumedha.commerce.e2e;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceHealthE2ETest extends E2ETestBase {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @Test
    void productHealthIsUp() {
        assertHealthUp("product", serviceUrls.product());
    }

    @Test
    void inventoryHealthIsUp() {
        assertHealthUp("inventory", serviceUrls.inventory());
    }

    @Test
    void cartHealthIsUp() {
        assertHealthUp("cart", serviceUrls.cart());
    }

    @Test
    void orderHealthIsUp() {
        assertHealthUp("order", serviceUrls.order());
    }

    @Test
    void paymentHealthIsUp() {
        assertHealthUp("payment", serviceUrls.payment());
    }

    @Test
    void checkoutHealthIsUp() {
        assertHealthUp("checkout", serviceUrls.checkout());
    }

    private void assertHealthUp(String serviceName, String baseUrl) {
        HttpRequest request = HttpRequest.newBuilder(healthUri(baseUrl))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
        HttpResponse<String> response;

        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            Assumptions.assumeTrue(false, () -> serviceName + " service is unavailable at " + baseUrl);
            throw new AssertionError("Unreachable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Assumptions.assumeTrue(false, () -> "Health check interrupted for " + serviceName + " service");
            throw new AssertionError("Unreachable");
        }

        assertEquals(200, response.statusCode(), serviceName + " health endpoint should return HTTP 200");
        assertTrue(response.body().matches("(?s).*\\\"status\\\"\\s*:\\s*\\\"UP\\\".*"),
                serviceName + " health endpoint should report status=UP");
    }

    private URI healthUri(String baseUrl) {
        return URI.create(baseUrl.replaceFirst("/+$", "") + "/actuator/health");
    }
}
