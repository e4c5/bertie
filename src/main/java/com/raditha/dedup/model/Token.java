package com.raditha.dedup.model;

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
 */
public record Token(
        TokenType type,
        String normalizedValue,
        String originalValue,
        String inferredType,
        int lineNumber,
        int columnNumber) {
    /**
     * Create a token with minimal information.
     */
    public Token(TokenType type, String normalizedValue, String originalValue) {
        this(type, normalizedValue, originalValue, null, 0, 0);
    }

    /**
     * Check if this token matches another token semantically.
     * Used for similarity comparison.
     */
    public boolean semanticallyMatches(Token other) {
        if (other == null)
            return false;
        return this.type == other.type &&
                this.normalizedValue.equals(other.normalizedValue);
    }
}
