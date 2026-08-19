package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.cart.CartGetResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class CartClient extends DownstreamClient {

    private static final ParameterizedTypeReference<DownstreamApiResponse<CartGetResponse>> CART_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public CartClient(@Value("${checkout.services.cart-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public CartGetResponse getCart(UUID cartId) {
        return execute(() -> restClient.get()
                .uri("/api/v1/carts/{cartId}", cartId)
                .retrieve()
                .body(CART_RESPONSE), "cart");
    }
}
