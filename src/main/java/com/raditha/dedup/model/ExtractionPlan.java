package com.raditha.dedup.model;

import java.util.List;

/**
 * Complete plan for extracting a method.
 * Contains both parameters (from varying expressions) and arguments (from
 * variable references).
 * 
 * @param parameters New parameters to create (from varying expressions)
 * @param arguments  Arguments to pass (from variable references)
 */
public record ExtractionPlan(
        List<ParameterSpec> parameters,
        List<ArgumentSpec> arguments) {
    /**
     * Get total number of method parameters (parameters + arguments).
     */
    public int totalParameterCount() {
        return parameters.size() + arguments.size();
    }

    /**
     * Check if there are any parameters or arguments.
     */
    public boolean hasParameters() {
        return !parameters.isEmpty() || !arguments.isEmpty();
    }
}
