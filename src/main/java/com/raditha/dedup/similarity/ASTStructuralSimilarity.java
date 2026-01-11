package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Calculates structural similarity based on AST node types.
 * Focuses on the structure of the code rather than exact matching.
 */
public class ASTStructuralSimilarity {

    /**
     * Calculate structural similarity between two sequences.
     * Compares the types and structure of AST nodes.
     * 
     * @param nodes1 First sequence
     * @param nodes2 Second sequence
     * @return Similarity score between 0.0 and 1.0
     */
    public double calculate(List<NormalizedNode> nodes1, List<NormalizedNode> nodes2) {
        if (nodes1.isEmpty() && nodes2.isEmpty()) {
            return 1.0;
        }

        if (nodes1.isEmpty() || nodes2.isEmpty()) {
            return 0.0;
        }

        // Compare sizes - structural similarity penalizes different lengths
        int minSize = Math.min(nodes1.size(), nodes2.size());
        int maxSize = Math.max(nodes1.size(), nodes2.size());

        // Count matching positions
        int matches = 0;
        for (int i = 0; i < minSize; i++) {
            if (nodesMatch(nodes1.get(i), nodes2.get(i))) {
                matches++;
            }
        }

        // Structural score considers both matching nodes and size difference
        return (double) matches / maxSize;
    }

    /**
     * Check if two normalized nodes match structurally.
     * 
     * @param n1 First node
     * @param n2 Second node
     * @return true if nodes have same normalized structure
     */
    private boolean nodesMatch(NormalizedNode n1, NormalizedNode n2) {
        return n1.structurallyEquals(n2);
    }
}
