package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.payment.CreatePaymentRequest;
import com.sumedha.commerce.checkout.dto.downstream.payment.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient extends DownstreamClient {

    private static final ParameterizedTypeReference<DownstreamApiResponse<PaymentResponse>> PAYMENT_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public PaymentClient(@Value("${checkout.services.payment-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public PaymentResponse createPayment(CreatePaymentRequest request) {
        return execute(() -> restClient.post()
                .uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PAYMENT_RESPONSE), "payment");
    }
}
