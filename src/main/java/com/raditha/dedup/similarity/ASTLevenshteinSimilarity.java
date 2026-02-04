package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Calculates similarity using Levenshtein (edit distance) algorithm.
 * Operates on normalized AST nodes for structural comparison.
 */
public class ASTLevenshteinSimilarity {

    /**
     * Calculate Levenshtein-based similarity between two sequences.
     *
     * @param nodes1 First sequence
     * @param nodes2 Second sequence
     * @return Similarity score between 0.0 and 1.0
     */
    public double calculate(List<NormalizedNode> nodes1, List<NormalizedNode> nodes2) {
        if (nodes1.isEmpty() && nodes2.isEmpty()) {
            return 1.0;
        }

        int distance = computeEditDistance(nodes1, nodes2);
        int maxLength = Math.max(nodes1.size(), nodes2.size());

        if (maxLength == 0) {
            return 1.0;
        }

        // Convert distance to similarity (0 distance = 1.0 similarity)
        return 1.0 - ((double) distance / maxLength);
    }

    /**
     * Compute Levenshtein edit distance between two sequences.
     * Uses dynamic programming approach.
     *
     * @param a First sequence
     * @param b Second sequence
     * @return Edit distance (number of insertions/deletions/substitutions needed)
     */
    private int computeEditDistance(List<NormalizedNode> a, List<NormalizedNode> b) {
        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];

        // Initialize base cases
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }

        // Fill the DP table
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (nodesMatch(a.get(i - 1), b.get(j - 1))) {
                    // No operation needed
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    // Take minimum of insert, delete, or substitute
                    dp[i][j] = 1 + Math.min(
                            dp[i - 1][j], // Delete
                            Math.min(
                                    dp[i][j - 1], // Insert
                                    dp[i - 1][j - 1] // Substitute
                            ));
                }
            }
        }

        return dp[m][n];
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
