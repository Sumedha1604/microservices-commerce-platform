package com.sumedha.commerce.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Instant;
import java.util.Date;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "gateway.security.jwt.secret=12345678901234567890123456789012",
        "gateway.security.jwt.issuer=commerce-auth-service"
})
@Import(GatewaySecurityTest.TestRoutes.class)
class GatewaySecurityTest {

    private static final String SECRET = "12345678901234567890123456789012";

    @org.springframework.beans.factory.annotation.Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void createClient() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://localhost").build();
    }

    @Test
    void publicReadRoutesAndAuthEndpointsDoNotRequireToken() {
        client.get().uri("/api/v1/products/one").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/search/products").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/recommendations/products/one").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void rejectsMissingMalformedBadSignatureAndExpiredTokens() throws Exception {
        client.get().uri("/api/v1/users/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(
                        token("abcdefghijabcdefghijabcdefghijabcdef", Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().minusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void acceptsValidTokenAndEnforcesAdminRole() throws Exception {
        client.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/admin/dlt/payment-events").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/admin/dlt/payment-events").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/admin/dlt/payment-events").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "ADMIN")))
                .exchange().expectStatus().isOk();
    }

    @Test
    void catalogueMutationRequiresAdmin() throws Exception {
        client.post().uri("/api/v1/products").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/products").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/products").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "ADMIN")))
                .exchange().expectStatus().isOk();
    }

    @Test
    void customerCannotSelectAnotherUsersProfile() {
        String ownId = "11111111-1111-1111-1111-111111111111";
        String otherId = "22222222-2222-2222-2222-222222222222";
        client.get().uri("/api/v1/users/" + ownId + "/profile").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/users/" + otherId + "/profile").headers(headers -> headers.setBearerAuth(
                        token(SECRET, Instant.now().plusSeconds(60), "CUSTOMER")))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void addsSecurityHeadersAndUsesExplicitNonCredentialedCors() {
        client.get().uri("/api/v1/products/one").exchange()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");

        client.method(HttpMethod.OPTIONS).uri("/api/v1/products/one")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    private static String token(String secret, Instant expiration, String role) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .issuer("commerce-auth-service")
                .issueTime(new Date())
                .expirationTime(Date.from(expiration))
                .claim("role", role)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new IllegalStateException(exception);
        }
        return jwt.serialize();
    }

    @TestConfiguration
    static class TestRoutes {
        @Bean
        RouterFunction<ServerResponse> securityTestRoutes() {
            return route(GET("/api/v1/products/{id}"), request -> ServerResponse.ok().build())
                    .andRoute(GET("/api/v1/search/products"), request -> ServerResponse.ok().build())
                    .andRoute(GET("/api/v1/recommendations/products/{id}"), request -> ServerResponse.ok().build())
                    .andRoute(POST("/api/v1/auth/login"), request -> ServerResponse.ok().build())
                    .andRoute(GET("/api/v1/users/me"), request -> ServerResponse.ok().build())
                    .andRoute(GET("/api/v1/users/{userId}/profile"), request -> ServerResponse.ok().build())
                    .andRoute(GET("/api/v1/admin/dlt/payment-events"), request -> ServerResponse.ok().build())
                    .andRoute(POST("/api/v1/products"), request -> ServerResponse.ok().build());
        }
    }
}
