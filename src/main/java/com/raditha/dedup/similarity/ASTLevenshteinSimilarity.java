package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Calculates similarity using Levenshtein (edit distance) algorithm.
 * Operates on normalized AST nodes for structural comparison.
 */
public class ASTLevenshteinSimilarity extends AbstractDPSimilarity {

    @Override
    protected RowInitializer getRowInitializer() {
        return row -> {
            for (int i = 0; i < row.length; i++) {
                row[i] = i;
            }
        };
    }

    @Override
    protected RowProcessor getRowProcessor() {
        return new RowProcessor() {
            @Override
            public void beforeRow(int[] currRow, int j) {
                currRow[0] = j;
            }

            @Override
            public void processCell(int[] prevRow, int[] currRow, int i, int j, NormalizedNode nodeA, NormalizedNode nodeB) {
                if (nodesMatch(nodeA, nodeB)) {
                    currRow[i] = prevRow[i - 1];
                } else {
                    currRow[i] = 1 + Math.min(prevRow[i], Math.min(currRow[i - 1], prevRow[i - 1]));
                }
            }
        };
    }

    @Override
    protected double convertResultToScore(int result, int maxLength) {
        return 1.0 - ((double) result / maxLength);
    }
}
