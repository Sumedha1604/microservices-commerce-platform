package com.sumedha.commerce.checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "checkout.http")
public record CheckoutHttpProperties(Duration connectTimeout, Duration readTimeout) {
}
