package com.sumedha.commerce.common.core.pagination;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageResponseTest {

    @Test
    void ofCreatesPageResponseWithCorrectValues() {
        List<String> items = List.of("first", "second", "third");

        PageResponse<String> response = PageResponse.of(items, 0, 3, 8);

        assertEquals(items, response.getItems());
        assertEquals(0, response.getPage());
        assertEquals(3, response.getSize());
        assertEquals(8, response.getTotalElements());
        assertEquals(3, response.getTotalPages());
        assertTrue(response.isHasNext());
        assertFalse(response.isHasPrevious());
    }

    @Test
    void ofCreatesLastPageCorrectly() {
        PageResponse<String> response = PageResponse.of(List.of("seventh", "eighth"), 2, 3, 8);

        assertEquals(3, response.getTotalPages());
        assertFalse(response.isHasNext());
        assertTrue(response.isHasPrevious());
    }

    @Test
    void ofHandlesEmptyResult() {
        PageResponse<String> response = PageResponse.of(List.of(), 0, 10, 0);

        assertEquals(0, response.getTotalPages());
        assertFalse(response.isHasNext());
        assertFalse(response.isHasPrevious());
        assertTrue(response.getItems().isEmpty());
    }

    @Test
    void ofCreatesImmutableItemsList() {
        List<String> items = new ArrayList<>(List.of("first"));
        PageResponse<String> response = PageResponse.of(items, 0, 10, 1);

        items.add("second");

        assertEquals(List.of("first"), response.getItems());
        assertThrows(UnsupportedOperationException.class, () -> response.getItems().add("third"));
    }

    @Test
    void ofRejectsNullItems() {
        assertThrows(NullPointerException.class, () -> PageResponse.of(null, 0, 10, 0));
    }

    @Test
    void ofRejectsNegativePage() {
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), -1, 10, 0));
    }

    @Test
    void ofRejectsInvalidSize() {
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), 0, 0, 0));
    }

    @Test
    void ofRejectsNegativeTotalElements() {
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), 0, 10, -1));
    }
}
