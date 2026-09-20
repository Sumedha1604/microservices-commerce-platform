package com.sumedha.commerce.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrustedIdentityHeadersFilterTest {

    private final TrustedIdentityHeadersFilter filter = new TrustedIdentityHeadersFilter();

    @Test
    void jwtIdentityReplacesClientSuppliedHeaders() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "HS256"), Map.of("sub", "trusted-user", "role", "CUSTOMER"));
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/protected")
                        .header(TrustedIdentityHeadersFilter.USER_ID_HEADER, "spoofed-user")
                        .header(TrustedIdentityHeadersFilter.USER_ROLE_HEADER, "ADMIN"))
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        assertEquals("trusted-user", forwarded.get().getFirst(TrustedIdentityHeadersFilter.USER_ID_HEADER));
        assertEquals("CUSTOMER", forwarded.get().getFirst(TrustedIdentityHeadersFilter.USER_ROLE_HEADER));
    }

    @Test
    void anonymousRequestCannotForwardIdentityHeaders() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/public")
                .header(TrustedIdentityHeadersFilter.USER_ID_HEADER, "spoofed-user"));
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        assertNull(forwarded.get().getFirst(TrustedIdentityHeadersFilter.USER_ID_HEADER));
    }
}
