package com.raditha.dedup.model;

import java.util.List;
import java.util.Set;

/**
 * Complete plan for extracting a method.
 * Contains parameters (from varying expressions), arguments (from variable references),
 * and context information about captured variables and outer field access.
 *
 * @param parameters         New parameters to create (from varying expressions)
 * @param arguments          Arguments to pass (from variable references)
 * @param capturedVariables  Variables captured from outer scope (lambdas/anonymous classes) - read-only
 * @param outerFieldAccess   Fields accessed from outer class (anonymous classes only)
 */
public record ExtractionPlan(
        List<ParameterSpec> parameters,
        List<VariableReference> arguments,
        Set<String> capturedVariables,
        Set<String> outerFieldAccess) {

    /**
     * Backward-compatible constructor without context info.
     */
    public ExtractionPlan(List<ParameterSpec> parameters, List<VariableReference> arguments) {
        this(parameters, arguments, Set.of(), Set.of());
    }

    /**
     * Check if there are any parameters or arguments.
     */
    public boolean hasParameters() {
        return !parameters.isEmpty() || !arguments.isEmpty();
    }

    /**
     * Check if a variable is captured from outer scope.
     * Captured variables are read-only and cannot be modified in the extracted method.
     */
    public boolean isCapturedVariable(String varName) {
        return capturedVariables.contains(varName);
    }

    /**
     * Check if a variable is accessing an outer class field.
     * These may need special handling during refactoring.
     */
    public boolean isOuterFieldAccess(String varName) {
        return outerFieldAccess.contains(varName);
    }

    /**
     * Check if this extraction requires access to outer class context.
     */
    public boolean requiresOuterClassContext() {
        return !outerFieldAccess.isEmpty();
    }
}
