package com.raditha.dedup.cli;

/**
 * Enumeration of verification modes for the Bertie CLI.
 * Used to control the level of verification after refactoring operations.
 */
public enum VerifyMode {
    /**
     * No verification - Skip all verification steps.
     * Fastest option but provides no safety guarantees.
     */
    NONE,
    
    /**
     * Compile verification - Verify that code compiles successfully.
     * This is the default mode that ensures basic correctness.
     */
    COMPILE,
    
    /**
     * Test verification - Run the full test suite.
     * Most thorough verification but takes the longest time.
     */
    TEST;
    
    /**
     * Convert a string value to VerifyMode enum.
     * 
     * @param value the string value to convert (case-insensitive)
     * @return the corresponding VerifyMode
     * @throws IllegalArgumentException if the value is not a valid mode
     */
    public static VerifyMode fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("VerifyMode value cannot be null");
        }
        
        return switch (value.toLowerCase()) {
            case "none" -> NONE;
            case "compile" -> COMPILE;
            case "test" -> TEST;
            default -> throw new IllegalArgumentException(
                "Invalid verify mode: " + value + ". Must be: none, compile, or test");
        };
    }
    
    /**
     * Get the string representation of this mode for CLI usage.
     * 
     * @return lowercase string representation
     */
    public String toCliString() {
        return switch (this) {
            case NONE -> "none";
            case COMPILE -> "compile";
            case TEST -> "test";
        };
    }
}