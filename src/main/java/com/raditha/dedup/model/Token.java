package com.raditha.dedup.model;

import com.github.javaparser.ast.expr.Expression;

/**
 * Represents a normalized token in a code sequence.
 * Preserves semantic meaning (method names, types) while normalizing
 * implementation details (variable names, literals).
 * 
 * @param type            Token type category
 * @param normalizedValue Normalized representation (e.g., "VAR",
 *                        "METHOD_CALL(save)")
 * @param originalValue   Original source code value (e.g., "userId",
 *                        "userRepo.save(user)")
 * @param inferredType    Inferred Java type (e.g., "String", "void", "User")
 * @param lineNumber      Source line number
 * @param columnNumber    Source column number
 * @param expr            The original AST Expression node (null for
 *                        non-expression nodes)
 */
public record Token(
        TokenType type,
        String normalizedValue,
        String originalValue,
        String inferredType,
        int lineNumber,
        int columnNumber,
        Expression expr) {
    /**
     * Create a token with minimal information.
     */
    public Token(TokenType type, String normalizedValue, String originalValue) {
        this(type, normalizedValue, originalValue, null, 0, 0, null);
    }

    /**
     * Create a token without Expression (backward compatibility).
     */
    public Token(TokenType type, String normalizedValue, String originalValue,
            String inferredType, int lineNumber, int columnNumber) {
        this(type, normalizedValue, originalValue, inferredType, lineNumber, columnNumber, null);
    }

    /**
     * Check if this token matches another token semantically.
     * Used for similarity comparison.
     * 
     * CRITICAL: For literals, we compare ORIGINAL values to detect variations.
     * For semantic tokens (method calls, types), we compare NORMALIZED values.
     */
    public boolean semanticallyMatches(Token other) {
        if (other == null)
            return false;

        // Must be same type
        if (this.type != other.type)
            return false;

        // For literals: compare NORMALIZED values to enable alignment
        // We will detect value mismatches in VariationTracker
        if (isLiteralType(this.type)) {
            return this.normalizedValue.equals(other.normalizedValue);
        }

        // For semantic tokens: compare NORMALIZED values
        // This enables similarity matching for method calls, types, etc.
        return this.normalizedValue.equals(other.normalizedValue);
    }

    /**
     * Check if a token type is a literal type (should compare original values).
     */
    private boolean isLiteralType(TokenType type) {
        return type == TokenType.STRING_LIT ||
                type == TokenType.INT_LIT ||
                type == TokenType.LONG_LIT ||
                type == TokenType.DOUBLE_LIT ||
                type == TokenType.BOOLEAN_LIT ||
                type == TokenType.NULL_LIT;
    }
}
