package com.sumedha.commerce.inventory.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRequestValidationTest {

    static Validator v;

    @BeforeAll
    static void init() {
        v = Validation.buildDefaultValidatorFactory().getValidator();
    }

    boolean bad(Object x) {
        return !v.validate(x).isEmpty();
    }

    @Test
    void validatesCreateInventoryRequest() {
        assertFalse(bad(new CreateInventoryRequest(UUID.randomUUID(), 10)));
        assertTrue(bad(new CreateInventoryRequest(null, 10)));
        assertTrue(bad(new CreateInventoryRequest(UUID.randomUUID(), null)));
        assertTrue(bad(new CreateInventoryRequest(UUID.randomUUID(), -1)));
        assertFalse(bad(new CreateInventoryRequest(UUID.randomUUID(), 0)));
    }

    @Test
    void validatesUpdateInventoryQuantityRequest() {
        assertFalse(bad(new UpdateInventoryQuantityRequest(5)));
        assertTrue(bad(new UpdateInventoryQuantityRequest(null)));
        assertTrue(bad(new UpdateInventoryQuantityRequest(-1)));
        assertFalse(bad(new UpdateInventoryQuantityRequest(0)));
    }

    @Test
    void validatesStockQuantityRequest() {
        assertFalse(bad(new StockQuantityRequest(1)));
        assertFalse(bad(new StockQuantityRequest(10)));
        assertTrue(bad(new StockQuantityRequest(0)));
        assertTrue(bad(new StockQuantityRequest(-1)));
        assertTrue(bad(new StockQuantityRequest(null)));
    }
}
