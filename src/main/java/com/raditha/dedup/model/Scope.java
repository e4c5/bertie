package com.raditha.dedup.model;

/**
 * Scope of a variable reference.
 * Used to determine how variables should be passed to extracted methods.
 */
public enum Scope {
    /**
     * Method parameter - should be passed through to extracted method
     */
    PARAMETER,

    /**
     * Local variable - should be passed through to extracted method
     */
    LOCAL_VAR,

    /**
     * Class field - may need special handling (this.field)
     */
    FIELD,

    /**
     * Unknown scope - resolution failed
     */
    UNKNOWN
}
