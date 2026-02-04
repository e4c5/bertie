package com.raditha.dedup.model;

import com.github.javaparser.ast.expr.Expression;

/**
 * Lightweight wrapper around an AST expression used as a bound value
 * for a variation at a specific sequence occurrence.
 *
 * Prefer using the AST node (expr) directly. If unavailable, we keep
 * the raw text and best-effort source location for reconstruction.
 */
public record ExprInfo(
        Expression expr,
        Integer startLine,
        Integer startColumn,
        String text) {

    /**
     * Creates ExprInfo from an AST expression.
     *
     * @param e The expression
     * @return ExprInfo
     */
    public static ExprInfo fromExpression(Expression e) {
        Integer line = null;
        Integer col = null;
        if (e != null && e.getRange().isPresent()) {
            line = e.getRange().get().begin.line;
            col = e.getRange().get().begin.column;
        }
        return new ExprInfo(e, line, col, e != null ? e.toString() : null);
    }

    /**
     * Creates ExprInfo from raw text (when AST is unavailable).
     *
     * @param text The expression text
     * @return ExprInfo
     */
    public static ExprInfo fromText(String text) {
        return new ExprInfo(null, null, null, text);
    }
}
