package com.sumedha.commerce.checkout.controller;

import com.sumedha.commerce.checkout.dto.response.CheckoutResponse;
import com.sumedha.commerce.checkout.exception.GlobalExceptionHandler;
import com.sumedha.commerce.checkout.service.CheckoutService;
import com.sumedha.commerce.common.core.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutControllerTest {

    private MockMvc mvc;
    private StubCheckoutService service;

    @BeforeEach
    void setUp() {
        service = new StubCheckoutService();
        mvc = MockMvcBuilders.standaloneSetup(new CheckoutController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createsCheckout() throws Exception {
        UUID cartId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        service.response = new CheckoutResponse(cartId, orderId, paymentId, "PENDING", "AUTHORIZED", BigDecimal.TEN, "USD");

        mvc.perform(post("/api/v1/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cartId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cartId").value(cartId.toString()))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId.toString()));
    }

    @Test
    void rejectsMissingCartId() throws Exception {
        mvc.perform(post("/api/v1/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void mapsCommerceExceptionToItsStatus() throws Exception {
        UUID cartId = UUID.randomUUID();
        service.failure = new ConflictException("Checkout conflict");

        mvc.perform(post("/api/v1/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cartId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Checkout conflict"));
    }

    @Test
    void sanitizesUnexpectedError() throws Exception {
        UUID cartId = UUID.randomUUID();
        service.failure = new RuntimeException("sensitive downstream detail");

        mvc.perform(post("/api/v1/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cartId + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    private static final class StubCheckoutService implements CheckoutService {

        private CheckoutResponse response;
        private RuntimeException failure;

        @Override
        public CheckoutResponse checkout(UUID cartId) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
