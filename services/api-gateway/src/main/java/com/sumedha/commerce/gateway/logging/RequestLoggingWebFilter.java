package com.sumedha.commerce.gateway.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Emits one INFO log line per completed HTTP request while the tracing span
 * scope (and therefore the traceId/spanId MDC entries) is still active, so
 * the line correlates with the request's trace in the log backend.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestLoggingWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        return chain.filter(exchange)
                .doFinally(signalType -> {
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value() : 0;
                    log.info("request completed method={} path={} status={} durationMs={}",
                            request.getMethod(), request.getPath().value(), status, durationMs);
                });
    }
}
