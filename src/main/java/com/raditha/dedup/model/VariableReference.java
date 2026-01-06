package com.raditha.dedup.model;

import com.github.javaparser.resolution.types.ResolvedType;

/**
 * Represents a variable reference found in duplicate code.
 * Used to identify variables that should be passed as arguments to extracted
 * methods.
 * 
 * @param name  Variable name
 * @param type  Resolved type of the variable
 * @param scope Scope of the variable (parameter, local, field, etc.)
 */
public record VariableReference(
        String name,
        ResolvedType type,
        Scope scope) {
    /**
     * Create a variable reference with unknown scope.
     */
    public static VariableReference unknown(String name) {
        return new VariableReference(name, null, Scope.UNKNOWN);
    }
}
