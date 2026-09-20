package com.sumedha.commerce.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UserOwnershipWebFilter implements WebFilter, Ordered {

    private static final Pattern USER_SCOPED_PATH = Pattern.compile(
            "^/api/v1/(?:users/([0-9a-fA-F-]{36})(?:/.*)?|(?:carts|orders|payments)/user/([0-9a-fA-F-]{36})(?:/.*)?)$");
    private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "SUPPORT");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Matcher matcher = USER_SCOPED_PATH.matcher(exchange.getRequest().getPath().value());
        if (!matcher.matches()) {
            return chain.filter(exchange);
        }
        String requestedUserId = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optional -> optional.map(authentication -> {
                    String role = authentication.getToken().getClaimAsString("role");
                    if (PRIVILEGED_ROLES.contains(role)
                            || authentication.getToken().getSubject().equals(requestedUserId)) {
                        return chain.filter(exchange);
                    }
                    return forbidden(exchange);
                }).orElseGet(() -> chain.filter(exchange)));
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        byte[] body = "{\"errorCode\":\"FORBIDDEN\",\"message\":\"Access is denied\",\"statusCode\":403}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
    }
}
