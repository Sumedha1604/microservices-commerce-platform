package com.sumedha.commerce.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter,
            ServerAuthenticationEntryPoint authenticationEntryPoint,
            ServerAccessDeniedHandler accessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.mode(
                                org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**",
                                "/api/v1/search/**", "/api/v1/recommendations/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewaySecurityProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must be at least 32 bytes");
        }
        var key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            Collection<GrantedAuthority> authorities = role == null || role.isBlank()
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewaySecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ignored) -> writeError(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @Bean
    ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ignored) -> writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied");
    }

    private static Mono<Void> writeError(
            org.springframework.web.server.ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        byte[] body = ("{\"errorCode\":\"" + code + "\",\"message\":\"" + message
                + "\",\"statusCode\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
