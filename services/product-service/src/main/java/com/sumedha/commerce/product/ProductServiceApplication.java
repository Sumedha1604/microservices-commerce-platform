package com.sumedha.commerce.product;
import com.sumedha.commerce.product.config.ProductOutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication @EnableScheduling @EnableConfigurationProperties(ProductOutboxProperties.class) public class ProductServiceApplication { public static void main(String[] args) { SpringApplication.run(ProductServiceApplication.class, args); } }
