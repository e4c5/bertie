package com.raditha.dedup.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class VerifyModeTest {

    @Test
    void testFromString_ValidValues() {
        assertEquals(VerifyMode.NONE, VerifyMode.fromString("none"));
        assertEquals(VerifyMode.COMPILE, VerifyMode.fromString("compile"));
        assertEquals(VerifyMode.TEST, VerifyMode.fromString("test"));
    }

    @Test
    void testFromString_CaseInsensitive() {
        assertEquals(VerifyMode.NONE, VerifyMode.fromString("NONE"));
        assertEquals(VerifyMode.NONE, VerifyMode.fromString("None"));
        assertEquals(VerifyMode.COMPILE, VerifyMode.fromString("COMPILE"));
        assertEquals(VerifyMode.COMPILE, VerifyMode.fromString("Compile"));
        assertEquals(VerifyMode.TEST, VerifyMode.fromString("TEST"));
        assertEquals(VerifyMode.TEST, VerifyMode.fromString("Test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unknown", "build", "", "compile-test"})
    void testFromString_InvalidValues(String invalidValue) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> VerifyMode.fromString(invalidValue)
        );
        assertTrue(exception.getMessage().contains("Invalid verify mode"));
        assertTrue(exception.getMessage().contains("Must be: none, compile, or test"));
    }

    @Test
    void testFromString_NullValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> VerifyMode.fromString(null)
        );
        assertEquals("VerifyMode value cannot be null", exception.getMessage());
    }

    @Test
    void testToCliString() {
        assertEquals("none", VerifyMode.NONE.toCliString());
        assertEquals("compile", VerifyMode.COMPILE.toCliString());
        assertEquals("test", VerifyMode.TEST.toCliString());
    }

    @Test
    void testRoundTrip() {
        // Test that fromString(toCliString()) returns the same enum
        for (VerifyMode mode : VerifyMode.values()) {
            assertEquals(mode, VerifyMode.fromString(mode.toCliString()));
        }
    }
}