package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Calculates similarity using Longest Common Subsequence (LCS) algorithm.
 * Operates on normalized AST nodes for structural comparison.
 */
public class ASTLCSSimilarity {

    /**
     * Calculate LCS-based similarity between two sequences of normalized nodes.
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

        int lcsLength = computeLCS(nodes1, nodes2);
        int maxLength = Math.max(nodes1.size(), nodes2.size());

        return (double) lcsLength / maxLength;
    }

    /**
     * Compute the length of the longest common subsequence.
     * Uses dynamic programming approach.
     *
     * @param a First sequence
     * @param b Second sequence
     * @return Length of LCS
     */
    private int computeLCS(List<NormalizedNode> a, List<NormalizedNode> b) {
        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];

        // Build LCS table
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (nodesMatch(a.get(i - 1), b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
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
