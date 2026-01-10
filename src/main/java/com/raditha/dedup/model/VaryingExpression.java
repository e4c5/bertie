package com.raditha.dedup.model;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.types.ResolvedType;

/**
 * Represents an expression that varies between duplicate code blocks.
 * These variations become parameters in the extracted method.
 * 
 * @param position Position in the statement sequence where variation occurs
 * @param expr1    Expression from first duplicate
 * @param expr2    Expression from second duplicate
 * @param type     Resolved type of the expressions
 */
public record VaryingExpression(
        int position,
        Expression expr1,
        Expression expr2,
        ResolvedType type) {
}
