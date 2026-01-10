package com.raditha.dedup.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RefactorModeTest {

    @Test
    void testFromString_ValidValues() {
        assertEquals(RefactorMode.INTERACTIVE, RefactorMode.fromString("interactive"));
        assertEquals(RefactorMode.BATCH, RefactorMode.fromString("batch"));
        assertEquals(RefactorMode.DRY_RUN, RefactorMode.fromString("dry-run"));
    }

    @Test
    void testFromString_CaseInsensitive() {
        assertEquals(RefactorMode.INTERACTIVE, RefactorMode.fromString("INTERACTIVE"));
        assertEquals(RefactorMode.INTERACTIVE, RefactorMode.fromString("Interactive"));
        assertEquals(RefactorMode.BATCH, RefactorMode.fromString("BATCH"));
        assertEquals(RefactorMode.BATCH, RefactorMode.fromString("Batch"));
        assertEquals(RefactorMode.DRY_RUN, RefactorMode.fromString("DRY-RUN"));
        assertEquals(RefactorMode.DRY_RUN, RefactorMode.fromString("Dry-Run"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unknown", "test", "", "interactive-mode"})
    void testFromString_InvalidValues(String invalidValue) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> RefactorMode.fromString(invalidValue)
        );
        assertTrue(exception.getMessage().contains("Invalid refactor mode"));
        assertTrue(exception.getMessage().contains("Must be: interactive, batch, or dry-run"));
    }

    @Test
    void testFromString_NullValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> RefactorMode.fromString(null)
        );
        assertEquals("RefactorMode value cannot be null", exception.getMessage());
    }

    @Test
    void testToCliString() {
        assertEquals("interactive", RefactorMode.INTERACTIVE.toCliString());
        assertEquals("batch", RefactorMode.BATCH.toCliString());
        assertEquals("dry-run", RefactorMode.DRY_RUN.toCliString());
    }

    @Test
    void testRoundTrip() {
        // Test that fromString(toCliString()) returns the same enum
        for (RefactorMode mode : RefactorMode.values()) {
            assertEquals(mode, RefactorMode.fromString(mode.toCliString()));
        }
    }
}