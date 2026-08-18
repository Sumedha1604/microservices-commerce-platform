package com.sumedha.commerce.cart.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartRequestValidationTest {

    static Validator v;

    @BeforeAll
    static void init() {
        v = Validation.buildDefaultValidatorFactory().getValidator();
    }

    boolean bad(Object x) {
        return !v.validate(x).isEmpty();
    }

    @Test
    void validatesCreateCartRequest() {
        assertFalse(bad(new CreateCartRequest(UUID.randomUUID())));
        assertTrue(bad(new CreateCartRequest(null)));
    }

    @Test
    void validatesAddCartItemRequest() {
        assertFalse(bad(new AddCartItemRequest(UUID.randomUUID(), 1)));
        assertTrue(bad(new AddCartItemRequest(null, 1)));
        assertTrue(bad(new AddCartItemRequest(UUID.randomUUID(), null)));
        assertTrue(bad(new AddCartItemRequest(UUID.randomUUID(), 0)));
        assertTrue(bad(new AddCartItemRequest(UUID.randomUUID(), -1)));
    }

    @Test
    void validatesUpdateCartItemQuantityRequest() {
        assertFalse(bad(new UpdateCartItemQuantityRequest(1)));
        assertTrue(bad(new UpdateCartItemQuantityRequest(null)));
        assertTrue(bad(new UpdateCartItemQuantityRequest(0)));
        assertTrue(bad(new UpdateCartItemQuantityRequest(-1)));
    }
}
