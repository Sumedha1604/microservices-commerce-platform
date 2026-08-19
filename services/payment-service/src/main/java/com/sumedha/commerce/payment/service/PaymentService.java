package com.sumedha.commerce.payment.service;

import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;

import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse create(CreatePaymentRequest request);

    PaymentResponse getById(UUID paymentId);

    PaymentResponse getByOrderId(UUID orderId);

    List<PaymentResponse> getByUserId(UUID userId);

    PaymentResponse authorize(UUID paymentId, AuthorizePaymentRequest request);

    PaymentResponse capture(UUID paymentId);

    PaymentResponse fail(UUID paymentId, FailPaymentRequest request);

    PaymentResponse cancel(UUID paymentId);

    PaymentResponse refund(UUID paymentId);
}
