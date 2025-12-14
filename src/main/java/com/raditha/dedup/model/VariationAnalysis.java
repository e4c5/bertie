package com.raditha.dedup.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Analysis of variations between two similar code sequences.
 * Categorizes differences by type for parameter extraction.
 * 
 * @param variations                All detected variations
 * @param hasControlFlowDifferences True if control structures differ
 */
public record VariationAnalysis(
        List<Variation> variations,
        boolean hasControlFlowDifferences) {
    /**
     * Get variations of a specific type.
     */
    public List<Variation> getVariationsOfType(VariationType type) {
        return variations.stream()
                .filter(v -> v.type() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get literal variations.
     */
    public List<Variation> getLiteralVariations() {
        return getVariationsOfType(VariationType.LITERAL);
    }

    /**
     * Get variable variations.
     */
    public List<Variation> getVariableVariations() {
        return getVariationsOfType(VariationType.VARIABLE);
    }

    /**
     * Get method call variations.
     */
    public List<Variation> getMethodCallVariations() {
        return getVariationsOfType(VariationType.METHOD_CALL);
    }

    /**
     * Get type variations.
     */
    public List<Variation> getTypeVariations() {
        return getVariationsOfType(VariationType.TYPE);
    }

    /**
     * Check if there are any variations.
     */
    public boolean hasVariations() {
        return !variations.isEmpty() || hasControlFlowDifferences;
    }

    /**
     * Get total number of variations.
     */
    public int getVariationCount() {
        return variations.size();
    }

    /**
     * Check if variations can be parameterized (all have type info, not too many).
     */
    public boolean canParameterize() {
        if (hasControlFlowDifferences)
            return false;
        if (variations.size() > 5)
            return false; // Max 5 parameters
        return variations.stream().allMatch(Variation::canParameterize);
    }
}
