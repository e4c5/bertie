package com.raditha.dedup.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for AIParameterNamer to verify graceful fallback.
 */
class AIParameterNamerTest {

    @Test
    void testAIAvailabilityCheck() {
        AIParameterNamer namer = new AIParameterNamer();

        // isAvailable() should return without throwing exceptions
        assertDoesNotThrow(namer::isAvailable, "Should not throw when checking availability");
    }

    @Test
    void testGracefulFallbackWhenAIUnavailable() {
        AIParameterNamer namer = new AIParameterNamer();

        // If AI is not available, should return null (for fallback)
        // If AI is available, should attempt to generate a name
        String result = namer.suggestName("test", List.of("test1", "test2"), null);

        // Result can be either a valid name or null (for fallback)
        if (result != null) {
            // If AI returned something, it should be a valid Java identifier
            assertTrue(result.matches("[a-z][a-zA-Z0-9]*"),
                    "AI result should be a valid Java identifier");
        }
        // If null, that's fine - means fallback to patterns
    }

    @Test
    void testValidationRejectsInvalidIdentifiers() {
        AIParameterNamer namer = new AIParameterNamer();

        // Test with various values - should always handle gracefully
        List.of("", "123invalid", "Invalid", "_underscore").forEach(invalid -> {
            String result = namer.suggestName(invalid, List.of(), null);
            // Should either return null or a valid identifier
            if (result != null) {
                assertTrue(result.matches("[a-z][a-zA-Z0-9]*"));
            }
        });
    }
}
