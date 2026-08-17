package com.sumedha.commerce.common.core.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SortDirectionTest {

    @Test
    void fromReturnsAscForUppercase() {
        assertEquals(SortDirection.ASC, SortDirection.from("ASC"));
    }

    @Test
    void fromReturnsDescForUppercase() {
        assertEquals(SortDirection.DESC, SortDirection.from("DESC"));
    }

    @Test
    void fromIsCaseInsensitive() {
        assertEquals(SortDirection.ASC, SortDirection.from("asc"));
        assertEquals(SortDirection.DESC, SortDirection.from("desc"));
    }

    @Test
    void fromIgnoresSurroundingWhitespace() {
        assertEquals(SortDirection.ASC, SortDirection.from("  ASC  "));
        assertEquals(SortDirection.DESC, SortDirection.from("\tDESC\n"));
    }

    @Test
    void fromRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> SortDirection.from(null));
    }

    @Test
    void fromRejectsUnsupportedValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SortDirection.from("UP")
        );

        assertEquals("Unsupported sort direction: UP", exception.getMessage());
    }

    @Test
    void fromRejectsBlankValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SortDirection.from("   ")
        );

        assertEquals(
                "Unsupported sort direction:    ",
                exception.getMessage()
        );
    }
}
