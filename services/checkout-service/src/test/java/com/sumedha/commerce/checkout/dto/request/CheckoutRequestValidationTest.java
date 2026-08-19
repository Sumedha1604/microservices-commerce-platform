package com.sumedha.commerce.checkout.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void requiresCartId() {
        assertTrue(validator.validate(new CheckoutRequest(UUID.randomUUID())).isEmpty());
        assertFalse(validator.validate(new CheckoutRequest(null)).isEmpty());
    }
}
