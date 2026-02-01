package com.raditha.dedup.model;

import java.util.List;
import java.util.ArrayList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

/**
 * Specification for a method parameter to be extracted during refactoring.
 * 
 */
public class ParameterSpec {
    private final String name;
    private final com.github.javaparser.ast.type.Type type;
    private final List<Expression> exampleValues;
    private final Integer variationIndex;
    private final Integer startLine;
    private final Integer startColumn;

    /**
     * Creates a new parameter specification.
     *
     * @param name           Parameter name
     * @param type           Parameter type
     * @param exampleValues  List of example values (as Expressions)
     * @param variationIndex Index of the variation this maps to (optional)
     * @param startLine      Start line of the original expression
     * @param startColumn    Start column of the original expression
     */
    public ParameterSpec(String name, com.github.javaparser.ast.type.Type type, List<Expression> exampleValues,
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

    /**
     * Gets the parameter name.
     *
     * @return parameter name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the parameter type.
     *
     * @return parameter type
     */
    public com.github.javaparser.ast.type.Type getType() {
        return type;
    }

    /**
     * Gets example values.
     *
     * @return list of example values
     */
    public List<Expression> getExampleValues() {
        return exampleValues;
    }

    /**
     * Gets the variation index.
     *
     * @return variation index or null
     */
    public Integer getVariationIndex() {
        return variationIndex;
    }

    /**
     * Gets the start line number.
     *
     * @return start line or null
     */
    public Integer getStartLine() {
        return startLine;
    }

    /**
     * Gets the start column number.
     *
     * @return start column or null
     */
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

    /**
     * Check if the given expression matches this parameter specification.
     * Uses AST structural equality against examples and robust type compatibility
     * for literals.
     * 
     * @param expr The expression to check
     * @param cu   The compilation unit context for type resolution
     */
    public boolean matches(Expression expr, CompilationUnit cu) {
        if (expr == null)
            return false;

        // 1. Check against examples using AST equality (structural match)
        for (Expression example : exampleValues) {
            if (example.equals(expr)) {
                return true;
            }
        }

        // 2. Type-based literal checks (using Antikythera's type resolution)
        if (expr.isLiteralExpr()) {
            TypeWrapper paramTypeWrapper = AbstractCompiler.findType(cu, this.type);

            com.github.javaparser.ast.type.Type litType = AbstractCompiler.convertLiteralToType(expr.asLiteralExpr());
            TypeWrapper litTypeWrapper = AbstractCompiler.findType(cu, litType);

            return paramTypeWrapper != null && litTypeWrapper != null && paramTypeWrapper.isAssignableFrom(litTypeWrapper);
        }

        return false;
    }
}
