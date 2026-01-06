package com.raditha.dedup.model;

import java.util.List;

/**
 * Specification for a method parameter to be extracted during refactoring.
 * 
 * @param name          Suggested parameter name
 * @param type          Java type for the parameter
 * @param exampleValues Example values from the duplicate code
 */
public class ParameterSpec {
    private final String name;
    private final transient com.github.javaparser.ast.type.Type type;
    private final List<String> exampleValues;
    private final Integer variationIndex;
    private final Integer startLine;
    private final Integer startColumn;

    public ParameterSpec(String name, com.github.javaparser.ast.type.Type type, List<String> exampleValues,
            Integer variationIndex, Integer startLine, Integer startColumn) {
        this.name = name;
        this.type = type;
        this.exampleValues = exampleValues;
        this.variationIndex = variationIndex;
        this.startLine = startLine;
        this.startColumn = startColumn;
    }

    /**
     * Create a parameter spec without example values.
     */
    public ParameterSpec(String name, com.github.javaparser.ast.type.Type type) {
        this(name, type, List.of(), null, null, null);
    }

    public String getName() {
        return name;
    }

    public com.github.javaparser.ast.type.Type getType() {
        return type;
    }

    public List<String> getExampleValues() {
        return exampleValues;
    }

    public Integer getVariationIndex() {
        return variationIndex;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getStartColumn() {
        return startColumn;
    }

    /**
     * Format as method parameter declaration.
     * Example: "String userId"
     */
    public String toParameterDeclaration() {
        return type.asString() + " " + name;
    }
}
