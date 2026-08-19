package com.sumedha.commerce.payment.controller;

import com.sumedha.commerce.common.core.api.ApiResponse;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> create(@Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentResponse> getById(@PathVariable("paymentId") UUID paymentId) {
        return ApiResponse.success(service.getById(paymentId));
    }

    @GetMapping("/order/{orderId}")
    public ApiResponse<PaymentResponse> getByOrderId(@PathVariable("orderId") UUID orderId) {
        return ApiResponse.success(service.getByOrderId(orderId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<PaymentResponse>> getByUserId(@PathVariable("userId") UUID userId) {
        return ApiResponse.success(service.getByUserId(userId));
    }

    @PostMapping("/{paymentId}/authorize")
    public ApiResponse<PaymentResponse> authorize(
            @PathVariable("paymentId") UUID paymentId, @Valid @RequestBody AuthorizePaymentRequest request) {
        return ApiResponse.success(service.authorize(paymentId, request));
    }

    @PostMapping("/{paymentId}/capture")
    public ApiResponse<PaymentResponse> capture(@PathVariable("paymentId") UUID paymentId) {
        return ApiResponse.success(service.capture(paymentId));
    }

    @PostMapping("/{paymentId}/fail")
    public ApiResponse<PaymentResponse> fail(
            @PathVariable("paymentId") UUID paymentId, @Valid @RequestBody FailPaymentRequest request) {
        return ApiResponse.success(service.fail(paymentId, request));
    }

    @PostMapping("/{paymentId}/cancel")
    public ApiResponse<PaymentResponse> cancel(@PathVariable("paymentId") UUID paymentId) {
        return ApiResponse.success(service.cancel(paymentId));
    }

    @PostMapping("/{paymentId}/refund")
    public ApiResponse<PaymentResponse> refund(@PathVariable("paymentId") UUID paymentId) {
        return ApiResponse.success(service.refund(paymentId));
    }
}
