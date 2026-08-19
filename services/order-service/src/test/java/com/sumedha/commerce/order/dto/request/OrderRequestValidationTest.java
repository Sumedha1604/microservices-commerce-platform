package com.sumedha.commerce.order.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderRequestValidationTest {

    static Validator v;

    @BeforeAll
    static void init() {
        v = Validation.buildDefaultValidatorFactory().getValidator();
    }

    boolean bad(Object x) {
        return !v.validate(x).isEmpty();
    }

    CreateOrderItemRequest validItem() {
        return new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", new BigDecimal("9.99"), 2);
    }

    @Test
    void validatesCreateOrderItemRequest() {
        assertFalse(bad(validItem()));
        assertTrue(bad(new CreateOrderItemRequest(null, "Widget", "SKU-1", new BigDecimal("9.99"), 2)));
        assertTrue(bad(new CreateOrderItemRequest(UUID.randomUUID(), " ", "SKU-1", new BigDecimal("9.99"), 2)));
        assertTrue(bad(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", new BigDecimal("-1.00"), 2)));
        assertTrue(bad(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", null, 2)));
        assertTrue(bad(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", new BigDecimal("9.99"), 0)));
        assertTrue(bad(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", new BigDecimal("9.99"), null)));
    }

    @Test
    void allowsBlankSkuOnItemRequest() {
        assertFalse(bad(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", null, new BigDecimal("9.99"), 1)));
    }

    @Test
    void validatesCreateOrderRequest() {
        assertFalse(bad(new CreateOrderRequest(UUID.randomUUID(), "USD", List.of(validItem()))));
        assertTrue(bad(new CreateOrderRequest(null, "USD", List.of(validItem()))));
        assertTrue(bad(new CreateOrderRequest(UUID.randomUUID(), "US", List.of(validItem()))));
        assertTrue(bad(new CreateOrderRequest(UUID.randomUUID(), "USDD", List.of(validItem()))));
        assertTrue(bad(new CreateOrderRequest(UUID.randomUUID(), "", List.of(validItem()))));
        assertTrue(bad(new CreateOrderRequest(UUID.randomUUID(), "USD", List.of())));
    }

    @Test
    void cascadesValidationToNestedItems() {
        CreateOrderItemRequest invalidItem =
                new CreateOrderItemRequest(null, "Widget", "SKU-1", new BigDecimal("9.99"), 1);

        assertTrue(bad(new CreateOrderRequest(UUID.randomUUID(), "USD", List.of(invalidItem))));
    }
}
