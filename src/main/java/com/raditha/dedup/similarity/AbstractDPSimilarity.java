package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;
import java.util.List;

/**
 * Abstract base class for DP-based similarity algorithms (LCS, Levenshtein).
 * Handles space-efficient O(min(M,N)) row management and input normalization.
 */
public abstract class AbstractDPSimilarity {

    /**
     * Calculate similarity using a DP approach.
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

        int result = computeSpaceEfficientDP(nodes1, nodes2, getRowInitializer(), getRowProcessor());
        int maxLength = Math.max(nodes1.size(), nodes2.size());
        return convertResultToScore(result, maxLength);
    }

    protected abstract RowInitializer getRowInitializer();
    protected abstract RowProcessor getRowProcessor();
    protected abstract double convertResultToScore(int result, int maxLength);

    /**
     * Helper to check if two normalized nodes match structurally.
     */
    protected boolean nodesMatch(NormalizedNode n1, NormalizedNode n2) {
        return n1.structurallyEquals(n2);
    }

    /**
     * Executes the DP algorithm using two rows to minimize space to O(min(M,N)).
     */
    protected int computeSpaceEfficientDP(
            List<NormalizedNode> a, 
            List<NormalizedNode> b, 
            RowInitializer initializer,
            RowProcessor processor) {
        
        int m = a.size();
        int n = b.size();

        // Ensure m <= n to minimize space
        if (m > n) {
            List<NormalizedNode> temp = a;
            a = b;
            b = temp;
            int tempM = m;
            m = n;
            n = tempM;
        }

        int[] prevRow = new int[m + 1];
        int[] currRow = new int[m + 1];

        initializer.initialize(prevRow);

        for (int j = 1; j <= n; j++) {
            processor.beforeRow(currRow, j);
            for (int i = 1; i <= m; i++) {
                processor.processCell(prevRow, currRow, i, j, a.get(i - 1), b.get(j - 1));
            }
            // Swap rows
            int[] temp = prevRow;
            prevRow = currRow;
            currRow = temp;
        }

        return prevRow[m];
    }

    @FunctionalInterface
    protected interface RowInitializer {
        void initialize(int[] row);
    }

    protected interface RowProcessor {
        default void beforeRow(int[] currRow, int j) {}
        void processCell(int[] prevRow, int[] currRow, int i, int j, NormalizedNode nodeA, NormalizedNode nodeB);
    }
}
