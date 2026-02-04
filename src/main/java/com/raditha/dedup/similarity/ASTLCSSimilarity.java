package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Calculates similarity using Longest Common Subsequence (LCS) algorithm.
 * Operates on normalized AST nodes for structural comparison.
 */
public class ASTLCSSimilarity extends AbstractDPSimilarity {

    @Override
    protected RowInitializer getRowInitializer() {
        return row -> java.util.Arrays.fill(row, 0);
    }

    @Override
    protected RowProcessor getRowProcessor() {
        return (prevRow, currRow, i, j, nodeA, nodeB) -> {
            if (nodesMatch(nodeA, nodeB)) {
                currRow[i] = prevRow[i - 1] + 1;
            } else {
                currRow[i] = Math.max(prevRow[i], currRow[i - 1]);
            }
        };
    }

    @Override
    protected double convertResultToScore(int result, int maxLength) {
        return (double) result / maxLength;
    }
}
