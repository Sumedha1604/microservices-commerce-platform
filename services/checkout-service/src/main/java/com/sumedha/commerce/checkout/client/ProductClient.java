package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.product.ProductGetResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class ProductClient extends DownstreamClient {

    private static final ParameterizedTypeReference<DownstreamApiResponse<ProductGetResponse>> PRODUCT_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public ProductClient(@Value("${checkout.services.product-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ProductGetResponse getProduct(UUID productId) {
        return execute(() -> restClient.get()
                .uri("/api/v1/products/{productId}", productId)
                .retrieve()
                .body(PRODUCT_RESPONSE), "product");
    }
}
