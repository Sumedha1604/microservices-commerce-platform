package com.sumedha.commerce.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.exception.GlobalExceptionHandler;
import com.sumedha.commerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    MockMvc mvc;
    PaymentService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PaymentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CreatePaymentRequest createRequest() {
        return new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "USD");
    }

    private PaymentResponse response(PaymentStatus status) {
        Instant now = Instant.now();
        return new PaymentResponse(paymentId, orderId, userId, status, new BigDecimal("10.00"), "USD",
                null, null, null, now, now);
    }

    // ---------- CREATE ----------

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        when(service.create(any())).thenReturn(response(PaymentStatus.PENDING));

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(service).create(any());
    }

    @Test
    void createRejectsNullOrderId() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(null, userId, new BigDecimal("10.00"), "USD");

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsInvalidCurrency() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(orderId, userId, new BigDecimal("10.00"), "US");

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsNegativeAmount() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(orderId, userId, new BigDecimal("-1.00"), "USD");

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createMapsConflictWhenPaymentAlreadyExistsForOrder() throws Exception {
        when(service.create(any())).thenThrow(new ConflictException("A payment already exists for this order"));

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void createMapsUnexpectedErrorToSanitizedResponse() throws Exception {
        when(service.create(any())).thenThrow(new RuntimeException("db connection string leaked here"));

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(createRequest())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ---------- GET BY ID ----------

    @Test
    void getByIdReturnsPayment() throws Exception {
        when(service.getById(paymentId)).thenReturn(response(PaymentStatus.PENDING));

        mvc.perform(get("/api/v1/payments/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(paymentId.toString()));
    }

    @Test
    void getByIdMapsResourceNotFound() throws Exception {
        when(service.getById(paymentId)).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(get("/api/v1/payments/{paymentId}", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment not found"));
    }

    @Test
    void getByIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/payments/{paymentId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- GET BY ORDER ----------

    @Test
    void getByOrderIdReturnsPayment() throws Exception {
        when(service.getByOrderId(orderId)).thenReturn(response(PaymentStatus.PENDING));

        mvc.perform(get("/api/v1/payments/order/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));
    }

    @Test
    void getByOrderIdMapsResourceNotFound() throws Exception {
        when(service.getByOrderId(orderId)).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(get("/api/v1/payments/order/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getByOrderIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/payments/order/{orderId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- GET BY USER ----------

    @Test
    void getByUserIdReturnsListNewestFirst() throws Exception {
        when(service.getByUserId(userId)).thenReturn(List.of(response(PaymentStatus.PENDING)));

        mvc.perform(get("/api/v1/payments/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(paymentId.toString()));
    }

    @Test
    void getByUserIdReturnsEmptyListWhenNoneFound() throws Exception {
        when(service.getByUserId(userId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/payments/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getByUserIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/payments/user/{userId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- AUTHORIZE ----------

    @Test
    void authorizeTransitionsPendingToAuthorized() throws Exception {
        when(service.authorize(eq(paymentId), any())).thenReturn(response(PaymentStatus.AUTHORIZED));

        mvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizePaymentRequest("stripe", "ref-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AUTHORIZED"));

        verify(service).authorize(eq(paymentId), any());
    }

    @Test
    void authorizeRejectsMissingProvider() throws Exception {
        AuthorizePaymentRequest request = new AuthorizePaymentRequest("", "ref-1");

        mvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void authorizeMapsResourceNotFound() throws Exception {
        when(service.authorize(eq(paymentId), any())).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizePaymentRequest("stripe", "ref-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void authorizeMapsInvalidTransitionToConflict() throws Exception {
        when(service.authorize(eq(paymentId), any()))
                .thenThrow(new ConflictException("Only pending payments can be authorized"));

        mvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizePaymentRequest("stripe", "ref-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Only pending payments can be authorized"));
    }

    @Test
    void authorizeWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/{paymentId}/authorize", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizePaymentRequest("stripe", "ref-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- CAPTURE ----------

    @Test
    void captureTransitionsAuthorizedToCaptured() throws Exception {
        when(service.capture(paymentId)).thenReturn(response(PaymentStatus.CAPTURED));

        mvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CAPTURED"));

        verify(service).capture(paymentId);
    }

    @Test
    void captureMapsResourceNotFound() throws Exception {
        when(service.capture(paymentId)).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void captureMapsInvalidTransitionToConflict() throws Exception {
        when(service.capture(paymentId)).thenThrow(new ConflictException("Only authorized payments can be captured"));

        mvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void captureWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/{paymentId}/capture", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- FAIL ----------

    @Test
    void failTransitionsPendingToFailed() throws Exception {
        when(service.fail(eq(paymentId), any())).thenReturn(response(PaymentStatus.FAILED));

        mvc.perform(post("/api/v1/payments/{paymentId}/fail", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FailPaymentRequest("card declined"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        verify(service).fail(eq(paymentId), any());
    }

    @Test
    void failRejectsBlankReason() throws Exception {
        FailPaymentRequest request = new FailPaymentRequest("");

        mvc.perform(post("/api/v1/payments/{paymentId}/fail", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void failMapsResourceNotFound() throws Exception {
        when(service.fail(eq(paymentId), any())).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(post("/api/v1/payments/{paymentId}/fail", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FailPaymentRequest("card declined"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void failMapsInvalidTransitionToConflict() throws Exception {
        when(service.fail(eq(paymentId), any()))
                .thenThrow(new ConflictException("Only pending or authorized payments can be failed"));

        mvc.perform(post("/api/v1/payments/{paymentId}/fail", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FailPaymentRequest("card declined"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void failWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/{paymentId}/fail", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FailPaymentRequest("card declined"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- CANCEL ----------

    @Test
    void cancelTransitionsToCancelled() throws Exception {
        when(service.cancel(paymentId)).thenReturn(response(PaymentStatus.CANCELLED));

        mvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(service).cancel(paymentId);
    }

    @Test
    void cancelMapsResourceNotFound() throws Exception {
        when(service.cancel(paymentId)).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void cancelMapsInvalidTransitionToConflict() throws Exception {
        when(service.cancel(paymentId))
                .thenThrow(new ConflictException("Only pending or authorized payments can be cancelled"));

        mvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void cancelWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/{paymentId}/cancel", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- REFUND ----------

    @Test
    void refundTransitionsCapturedToRefunded() throws Exception {
        when(service.refund(paymentId)).thenReturn(response(PaymentStatus.REFUNDED));

        mvc.perform(post("/api/v1/payments/{paymentId}/refund", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));

        verify(service).refund(paymentId);
    }

    @Test
    void refundMapsResourceNotFound() throws Exception {
        when(service.refund(paymentId)).thenThrow(new ResourceNotFoundException("Payment not found"));

        mvc.perform(post("/api/v1/payments/{paymentId}/refund", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void refundMapsInvalidTransitionToConflict() throws Exception {
        when(service.refund(paymentId)).thenThrow(new ConflictException("Only captured payments can be refunded"));

        mvc.perform(post("/api/v1/payments/{paymentId}/refund", paymentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void refundWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/{paymentId}/refund", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- Optimistic locking ----------

    @Test
    void captureMapsOptimisticLockingFailureToConflict() throws Exception {
        when(service.capture(paymentId)).thenThrow(new ObjectOptimisticLockingFailureException(Object.class, paymentId.toString()));

        mvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Payment was modified concurrently. Please retry."));
    }
}
