package com.sumedha.commerce.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class TrustedIdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange sanitized = exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(USER_ID_HEADER);
            headers.remove(USER_ROLE_HEADER);
        })).build();

        return sanitized.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> authentication
                        .map(auth -> chain.filter(sanitized.mutate().request(request -> request.headers(headers -> {
                            headers.set(USER_ID_HEADER, auth.getToken().getSubject());
                            String role = auth.getToken().getClaimAsString("role");
                            if (role != null && !role.isBlank()) {
                                headers.set(USER_ROLE_HEADER, role);
                            }
                        })).build()))
                        .orElseGet(() -> chain.filter(sanitized)));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
