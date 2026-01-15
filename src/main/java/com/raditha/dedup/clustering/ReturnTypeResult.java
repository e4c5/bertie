package com.raditha.dedup.clustering;

/**
 * Result of return type resolution.
 * 
 * @param returnType The resolved return type (e.g., "String", "User", "void")
 * @param returnVariable The name of the variable to return (if applicable)
 */
public record ReturnTypeResult(String returnType, String returnVariable) {
    
    /**
     * Create a void return result.
     */
    public static ReturnTypeResult voidResult() {
        return new ReturnTypeResult("void", null);
    }
}
