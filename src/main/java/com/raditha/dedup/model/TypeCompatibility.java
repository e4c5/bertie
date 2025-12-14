package com.raditha.dedup.model;

import java.util.List;
import java.util.Map;

/**
 * Analysis of type compatibility for parameter extraction.
 * Ensures variations can be safely parameterized with consistent types.
 * 
 * @param allVariationsTypeSafe True if all variations have compatible types
 * @param parameterTypes        Map of parameter names to their inferred types
 * @param inferredReturnType    Inferred return type for extracted method
 * @param warnings              Type safety warnings
 */
public record TypeCompatibility(
        boolean allVariationsTypeSafe,
        Map<String, String> parameterTypes,
        String inferredReturnType,
        List<String> warnings) {
    /**
     * Check if refactoring is type-safe.
     */
    public boolean isTypeSafe() {
        return allVariationsTypeSafe && warnings.isEmpty();
    }

    /**
     * Get number of parameters that would be extracted.
     */
    public int getParameterCount() {
        return parameterTypes != null ? parameterTypes.size() : 0;
    }

    /**
     * Check if parameter extraction is feasible (type-safe and reasonable parameter
     * count).
     */
    public boolean isFeasible() {
        return isTypeSafe() && getParameterCount() <= 5;
    }
}
