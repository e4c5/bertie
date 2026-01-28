package com.raditha.dedup.normalization;

import com.github.javaparser.ast.stmt.Statement;

/**
 * Holds both normalized and original AST for a statement.
 * Used for fuzzy duplicate detection while preserving original code.
 *
 * @param normalized Statement with literals replaced by placeholders
 * @param original   Original statement with actual values
 */
public record NormalizedNode(Statement normalized, Statement original) {

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

        // Compare normalized AST as strings
        // This preserves structure while ignoring literal values
        return this.normalized.toString().equals(other.normalized.toString());
    }
}
