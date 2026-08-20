package com.sumedha.commerce.gateway;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class GatewayRouteConfigurationTest {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void configuresAllBusinessApiRoutesWithUnchangedPaths() {
        Map<String, String> routes = gatewayProperties.getRoutes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        route -> route.getPredicates().getFirst().getArgs().get("_genkey_0"),
                        route -> route.getUri().toString()));

        assertEquals(10, routes.size());
        assertEquals("http://localhost:8081", routes.get("/api/v1/auth/**"));
        assertEquals("http://localhost:8082", routes.get("/api/v1/users/**"));
        assertEquals("http://localhost:8083", routes.get("/api/v1/products/**"));
        assertEquals("http://localhost:8083", routes.get("/api/v1/categories/**"));
        assertEquals("http://localhost:8083", routes.get("/api/v1/brands/**"));
        assertEquals("http://localhost:8084", routes.get("/api/v1/inventory/**"));
        assertEquals("http://localhost:8085", routes.get("/api/v1/carts/**"));
        assertEquals("http://localhost:8086", routes.get("/api/v1/orders/**"));
        assertEquals("http://localhost:8087", routes.get("/api/v1/payments/**"));
        assertEquals("http://localhost:8088", routes.get("/api/v1/checkouts/**"));
    }
}
