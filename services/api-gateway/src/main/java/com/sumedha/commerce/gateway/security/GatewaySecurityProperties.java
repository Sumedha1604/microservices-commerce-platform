package com.sumedha.commerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(Jwt jwt, Cors cors) {
    public record Jwt(String secret, String issuer) {}
    public record Cors(List<String> allowedOrigins) {}
}
