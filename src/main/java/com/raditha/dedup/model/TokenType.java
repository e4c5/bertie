package com.raditha.dedup.model;

/**
 * Type of normalized token in the duplicate detection process.
 * Tokens preserve semantic meaning (method names, types, operators)
 * while normalizing implementation details (variable names, literals).
 */
public enum TokenType {
    /** Variable name (normalized from userId, customerId, etc.) */
    VAR,

    /**
     * Method call with preserved method name (e.g., METHOD_CALL(save),
     * METHOD_CALL(setActive))
     */
    METHOD_CALL,

    /** String literal (normalized) */
    STRING_LIT,

    /** Integer literal (normalized) */
    INT_LIT,

    /** Long literal (normalized) */
    LONG_LIT,

    /** Double/Float literal (normalized) */
    DOUBLE_LIT,

    /** Boolean literal (normalized) */
    BOOLEAN_LIT,

    /** Null literal */
    NULL_LIT,

    /**
     * Type reference with preserved type name (e.g., TYPE(User), TYPE(Customer))
     */
    TYPE,

    /** Control flow keyword (if, for, while, switch, try, catch, finally, etc.) */
    CONTROL_FLOW,

    /** Operator (==, !=, &&, ||, +, -, etc.) */
    OPERATOR,

    /** Test assertion (assertEquals, assertTrue, assertNotNull, etc.) */
    ASSERT,

    /** Mockito method (when, verify, mock, etc.) */
    MOCK,

    /** Keyword (return, new, this, super, etc.) */
    KEYWORD,

    /** Other/unknown token type */
    OTHER
}
