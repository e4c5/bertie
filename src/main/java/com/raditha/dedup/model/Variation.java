package com.raditha.dedup.model;

/**
 * Represents a difference between two similar code sequences.
 * Used for parameter extraction and refactoring recommendations.
 * 
 * @param type          Type of variation (literal, variable, method call, type)
 * @param alignedIndex1 Position in first sequence (aligned via LCS)
 * @param alignedIndex2 Position in second sequence (aligned via LCS)
 * @param value1        Value in first sequence
 * @param value2        Value in second sequence
 * @param inferredType  Inferred Java type for this variation
 */
public record Variation(
        VariationType type,
        int alignedIndex1,
        int alignedIndex2,
        String value1,
        String value2,
        String inferredType) {
    /**
     * Create a variation without type information.
     */
    public Variation(VariationType type, int index1, int index2, String value1, String value2) {
        this(type, index1, index2, value1, value2, null);
    }

    /**
     * Check if this variation can be safely parameterized.
     * Requires consistent types between values.
     */
    public boolean canParameterize() {
        return inferredType != null && !inferredType.isEmpty();
    }
}
