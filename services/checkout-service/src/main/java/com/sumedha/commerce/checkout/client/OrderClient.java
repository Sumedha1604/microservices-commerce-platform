package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.OrderResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class OrderClient extends DownstreamClient {

    private static final ParameterizedTypeReference<DownstreamApiResponse<OrderResponse>> ORDER_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public OrderClient(@Value("${checkout.services.order-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public OrderResponse createOrder(CreateOrderRequest request) {
        return execute(() -> restClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ORDER_RESPONSE), "order");
    }

    public OrderResponse cancelOrder(UUID orderId) {
        return execute(() -> restClient.post()
                .uri("/api/v1/orders/{orderId}/cancel", orderId)
                .retrieve()
                .body(ORDER_RESPONSE), "order");
    }
}
