package com.sumedha.commerce.payment.service;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.mapper.PaymentMapper;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final int MONEY_SCALE = 2;
    private static final String ORDER_ID_UNIQUE_CONSTRAINT = "uq_payments_order_id";

    private final PaymentRepository payments;

    public PaymentServiceImpl(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional
    public PaymentResponse create(CreatePaymentRequest request) {
        if (payments.existsByOrderId(request.orderId())) {
            throw new ConflictException("A payment already exists for this order");
        }

        String currency = request.currency().toUpperCase();
        var amount = request.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        Payment payment;
        try {
            payment = payments.saveAndFlush(new Payment(request.orderId(), request.userId(), amount, currency));
        } catch (DataIntegrityViolationException e) {
            if (isOrderIdUniqueViolation(e)) {
                throw new ConflictException("A payment already exists for this order");
            }
            throw e;
        }
        return PaymentMapper.toResponse(payment);
    }

    /**
     * Distinguishes the losing side of a concurrent duplicate-order create (existsByOrderId
     * is a fast path, not a lock) from any other integrity failure, which must not be
     * reported as a conflict.
     */
    private static boolean isOrderIdUniqueViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && ORDER_ID_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getById(UUID paymentId) {
        return PaymentMapper.toResponse(payment(paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(UUID orderId) {
        Payment payment = payments.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        return PaymentMapper.toResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getByUserId(UUID userId) {
        return payments.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentMapper::toResponse)
                .toList();
    }

    @Transactional
    public PaymentResponse authorize(UUID paymentId, AuthorizePaymentRequest request) {
        Payment payment = payment(paymentId);
        payment.authorize(request.provider(), request.providerReference());
        return PaymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse capture(UUID paymentId) {
        Payment payment = payment(paymentId);
        payment.capture();
        return PaymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse fail(UUID paymentId, FailPaymentRequest request) {
        Payment payment = payment(paymentId);
        payment.fail(request.reason());
        return PaymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse cancel(UUID paymentId) {
        Payment payment = payment(paymentId);
        payment.cancel();
        return PaymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse refund(UUID paymentId) {
        Payment payment = payment(paymentId);
        payment.refund();
        return PaymentMapper.toResponse(payment);
    }

    private Payment payment(UUID id) {
        return payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }
}
