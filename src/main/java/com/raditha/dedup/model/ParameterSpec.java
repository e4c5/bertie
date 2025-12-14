package com.raditha.dedup.model;

import java.util.List;

/**
 * Specification for a method parameter to be extracted during refactoring.
 * 
 * @param name          Suggested parameter name
 * @param type          Java type for the parameter
 * @param exampleValues Example values from the duplicate code
 */
public record ParameterSpec(
        String name,
        String type,
        List<String> exampleValues) {
    /**
     * Create a parameter spec without example values.
     */
    public ParameterSpec(String name, String type) {
        this(name, type, List.of());
    }

    /**
     * Format as method parameter declaration.
     * Example: "String userId"
     */
    public String toParameterDeclaration() {
        return type + " " + name;
    }
}
