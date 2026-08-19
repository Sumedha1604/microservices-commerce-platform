package com.sumedha.commerce.payment.service;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    PaymentRepository payments;

    PaymentServiceImpl service;
    UUID paymentId;
    UUID orderId;
    UUID userId;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(payments);
        paymentId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    private Payment pendingPayment() {
        return new Payment(orderId, userId, new BigDecimal("10.00"), "USD");
    }

    // ---- create ----

    @Test
    void createsPaymentWithPendingStatus() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        PaymentResponse response = service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "USD"));

        assertEquals(PaymentStatus.PENDING, response.status());
    }

    @Test
    void normalizesAmountScaleUsingHalfUp() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        PaymentResponse response = service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.005"), "USD"));

        assertEquals(new BigDecimal("10.01"), response.amount());
    }

    @Test
    void normalizesCurrencyToUppercase() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        PaymentResponse response = service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "usd"));

        assertEquals("USD", response.currency());
    }

    @Test
    void rejectsDuplicatePaymentForSameOrder() {
        when(payments.existsByOrderId(orderId)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "USD")));
    }

    @Test
    void translatesLosingConcurrentDuplicateCreateToConflict() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "duplicate key value violates unique constraint",
                new SQLException("duplicate key value violates unique constraint \"uq_payments_order_id\""),
                "uq_payments_order_id");
        when(payments.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", constraintViolation));

        assertThrows(ConflictException.class,
                () -> service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "USD")));
    }

    @Test
    void doesNotMaskUnrelatedIntegrityViolationsAsDuplicateConflict() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "check constraint violated",
                new SQLException("new row violates check constraint \"payments_amount_check\""),
                "payments_amount_check");
        when(payments.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", constraintViolation));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "USD")));
    }

    // ---- get ----

    @Test
    void getByIdReturnsPayment() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.getById(payment.getId());

        assertEquals(payment.getId(), response.id());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(paymentId));
    }

    @Test
    void getByOrderIdReturnsPayment() {
        Payment payment = pendingPayment();
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        PaymentResponse response = service.getByOrderId(orderId);

        assertEquals(orderId, response.orderId());
    }

    @Test
    void getByOrderIdThrowsWhenMissing() {
        when(payments.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getByOrderId(orderId));
    }

    @Test
    void getByUserIdReturnsList() {
        Payment payment = pendingPayment();
        when(payments.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(payment));

        List<PaymentResponse> response = service.getByUserId(userId);

        assertEquals(1, response.size());
        assertEquals(payment.getId(), response.get(0).id());
    }

    @Test
    void getByUserIdReturnsEmptyListWhenNoneFound() {
        when(payments.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        List<PaymentResponse> response = service.getByUserId(userId);

        assertTrue(response.isEmpty());
    }

    // ---- authorize ----

    @Test
    void authorizeTransitionsPendingToAuthorized() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.authorize(payment.getId(), new AuthorizePaymentRequest("stripe", "ref-1"));

        assertEquals(PaymentStatus.AUTHORIZED, response.status());
        assertEquals("stripe", response.provider());
        assertEquals("ref-1", response.providerReference());
    }

    @Test
    void authorizeRejectsInvalidState() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class,
                () -> service.authorize(payment.getId(), new AuthorizePaymentRequest("stripe", "ref-2")));
    }

    @Test
    void authorizeThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1")));
    }

    // ---- capture ----

    @Test
    void captureTransitionsAuthorizedToCaptured() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.capture(payment.getId());

        assertEquals(PaymentStatus.CAPTURED, response.status());
    }

    @Test
    void captureRejectsInvalidState() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> service.capture(payment.getId()));
    }

    @Test
    void captureThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.capture(paymentId));
    }

    // ---- fail ----

    @Test
    void failTransitionsPendingToFailed() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.fail(payment.getId(), new FailPaymentRequest("card declined"));

        assertEquals(PaymentStatus.FAILED, response.status());
        assertEquals("card declined", response.failureReason());
    }

    @Test
    void failTransitionsAuthorizedToFailed() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.fail(payment.getId(), new FailPaymentRequest("issuer declined"));

        assertEquals(PaymentStatus.FAILED, response.status());
    }

    @Test
    void failRejectsInvalidState() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        payment.capture();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class,
                () -> service.fail(payment.getId(), new FailPaymentRequest("too late")));
    }

    @Test
    void failThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.fail(paymentId, new FailPaymentRequest("card declined")));
    }

    // ---- cancel ----

    @Test
    void cancelTransitionsPendingToCancelled() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.cancel(payment.getId());

        assertEquals(PaymentStatus.CANCELLED, response.status());
    }

    @Test
    void cancelTransitionsAuthorizedToCancelled() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.cancel(payment.getId());

        assertEquals(PaymentStatus.CANCELLED, response.status());
    }

    @Test
    void cancelRejectsInvalidState() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        payment.capture();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> service.cancel(payment.getId()));
    }

    @Test
    void cancelThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancel(paymentId));
    }

    // ---- refund ----

    @Test
    void refundTransitionsCapturedToRefunded() {
        Payment payment = pendingPayment();
        payment.authorize("stripe", "ref-1");
        payment.capture();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = service.refund(payment.getId());

        assertEquals(PaymentStatus.REFUNDED, response.status());
    }

    @Test
    void refundRejectsInvalidState() {
        Payment payment = pendingPayment();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThrows(ConflictException.class, () -> service.refund(payment.getId()));
    }

    @Test
    void refundThrowsWhenMissing() {
        when(payments.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.refund(paymentId));
    }
}
