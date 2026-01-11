package com.raditha.dedup.cli;

/**
 * Enumeration of refactoring modes for the Bertie CLI.
 * Used to control how refactoring operations are executed.
 */
public enum RefactorMode {
    /**
     * Interactive mode - Review each change before applying.
     * This is the default mode that allows user confirmation.
     */
    INTERACTIVE,
    
    /**
     * Batch mode - Auto-apply high-confidence refactorings only.
     * Skips user interaction for automated processing.
     */
    BATCH,
    
    /**
     * Dry-run mode - Preview changes without making modifications.
     * Shows what would be changed without actually modifying files.
     */
    DRY_RUN;
    
    /**
     * Convert a string value to RefactorMode enum.
     * 
     * @param value the string value to convert (case-insensitive)
     * @return the corresponding RefactorMode
     * @throws IllegalArgumentException if the value is not a valid mode
     */
    public static RefactorMode fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("RefactorMode value cannot be null");
        }
        
        return switch (value.toLowerCase()) {
            case "interactive" -> INTERACTIVE;
            case "batch" -> BATCH;
            case "dry-run" -> DRY_RUN;
            default -> throw new IllegalArgumentException(
                "Invalid refactor mode: " + value + ". Must be: interactive, batch, or dry-run");
        };
    }
    
    /**
     * Get the string representation of this mode for CLI usage.
     * 
     * @return lowercase string representation
     */
    public String toCliString() {
        return switch (this) {
            case INTERACTIVE -> "interactive";
            case BATCH -> "batch";
            case DRY_RUN -> "dry-run";
        };
    }
}