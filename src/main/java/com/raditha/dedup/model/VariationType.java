package com.raditha.dedup.model;

/**
 * Type of variation between similar code blocks.
 * Used to categorize differences for parameter extraction.
 */
public enum VariationType {
    /** Literal value difference (e.g., "PENDING" vs "APPROVED") */
    LITERAL,

    /** Variable name difference (e.g., userId vs customerId) */
    VARIABLE,

    /** Method call difference (e.g., getUser() vs getCustomer()) */
    METHOD_CALL,

    /** Type difference (e.g., User vs Customer) */
    TYPE,

    /** Control flow difference (structural mismatch) */
    CONTROL_FLOW
}
