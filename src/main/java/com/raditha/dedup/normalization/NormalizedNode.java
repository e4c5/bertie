package com.raditha.dedup.normalization;

import com.github.javaparser.ast.stmt.Statement;

/**
 * Holds both normalized and original AST for a statement.
 * Used for fuzzy duplicate detection while preserving original code.
 */
public class NormalizedNode {
    private final com.github.javaparser.ast.stmt.Statement normalized;
    private final com.github.javaparser.ast.stmt.Statement original;
    private final String cachedString;

    public NormalizedNode(com.github.javaparser.ast.stmt.Statement normalized, com.github.javaparser.ast.stmt.Statement original) {
        this.normalized = normalized;
        this.original = original;
        this.cachedString = normalized.toString();
    }

    public com.github.javaparser.ast.stmt.Statement normalized() {
        return normalized;
    }

    public com.github.javaparser.ast.stmt.Statement original() {
        return original;
    }

    /**
     * Check if this node is structurally equivalent to another.
     * Compares normalized AST (ignoring literal values).
     *
     * @param other Node to compare with
     * @return true if structures match
     */
    public boolean structurallyEquals(NormalizedNode other) {
        if (other == null) {
            return false;
        }

        // Compare cached normalized AST strings
        // This preserves structure while ignoring literal values and avoids expensive repeated toString()
        return this.cachedString.equals(other.cachedString);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NormalizedNode that = (NormalizedNode) o;
        return java.util.Objects.equals(cachedString, that.cachedString);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(cachedString);
    }
}
