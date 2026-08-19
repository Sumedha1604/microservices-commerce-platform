package com.sumedha.commerce.payment.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentRequestValidationTest {

    static Validator v;

    @BeforeAll
    static void init() {
        v = Validation.buildDefaultValidatorFactory().getValidator();
    }

    boolean bad(Object x) {
        return !v.validate(x).isEmpty();
    }

    @Test
    void validatesCreatePaymentRequest() {
        assertFalse(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "USD")));
        assertTrue(bad(new CreatePaymentRequest(null, UUID.randomUUID(), new BigDecimal("9.99"), "USD")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), null, new BigDecimal("9.99"), "USD")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("-1.00"), "USD")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), null, "USD")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "US")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "USDD")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "US1")));
        assertTrue(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "")));
    }

    @Test
    void allowsExtraFractionalDigitsSoServiceCanStillRoundHalfUp() {
        // 10.005 must remain valid at the DTO layer; PaymentServiceImpl normalizes it to 10.01.
        assertFalse(bad(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.005"), "USD")));
    }

    @Test
    void acceptsMaximumAmountSupportedByNumeric19x2() {
        assertFalse(bad(new CreatePaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("99999999999999999.99"), "USD")));
    }

    @Test
    void rejectsAmountThatWouldOverflowNumeric19x2AfterHalfUpRounding() {
        // 99999999999999999.995 rounds half-up to 100000000000000000.00 (18 integer digits),
        // which exceeds NUMERIC(19,2)'s 17-digit integer part.
        assertTrue(bad(new CreatePaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("99999999999999999.995"), "USD")));
        assertTrue(bad(new CreatePaymentRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100000000000000000.00"), "USD")));
    }

    @Test
    void validatesAuthorizePaymentRequest() {
        assertFalse(bad(new AuthorizePaymentRequest("stripe", "ref-123")));
        assertTrue(bad(new AuthorizePaymentRequest(" ", "ref-123")));
        assertTrue(bad(new AuthorizePaymentRequest("stripe", " ")));
    }

    @Test
    void acceptsProviderAtMaxLength() {
        assertFalse(bad(new AuthorizePaymentRequest("p".repeat(50), "ref-123")));
    }

    @Test
    void rejectsProviderOverMaxLength() {
        assertTrue(bad(new AuthorizePaymentRequest("p".repeat(51), "ref-123")));
    }

    @Test
    void acceptsProviderReferenceAtMaxLength() {
        assertFalse(bad(new AuthorizePaymentRequest("stripe", "r".repeat(255))));
    }

    @Test
    void rejectsProviderReferenceOverMaxLength() {
        assertTrue(bad(new AuthorizePaymentRequest("stripe", "r".repeat(256))));
    }

    @Test
    void validatesFailPaymentRequest() {
        assertFalse(bad(new FailPaymentRequest("card declined")));
        assertTrue(bad(new FailPaymentRequest(" ")));
        assertTrue(bad(new FailPaymentRequest("x".repeat(501))));
    }
}
