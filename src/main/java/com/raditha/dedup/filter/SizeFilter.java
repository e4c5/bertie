package com.raditha.dedup.filter;

import com.raditha.dedup.model.StatementSequence;

/**
 * Filters sequence pairs based on size difference.
 * Eliminates ~95% of comparisons by skipping pairs with >30% size difference.
 */
public class SizeFilter {

    private final double maxDifferenceRatio;

    /**
     * Create filter with default 30% max difference.
     */
    public SizeFilter() {
        this(0.30);
    }

    /**
     * Create filter with custom max difference ratio.
     * 
     * @param maxDifferenceRatio Maximum allowed size difference (0.0 to 1.0)
     */
    public SizeFilter(double maxDifferenceRatio) {
        if (maxDifferenceRatio < 0.0 || maxDifferenceRatio > 1.0) {
            throw new IllegalArgumentException("Max difference ratio must be between 0.0 and 1.0");
        }
        this.maxDifferenceRatio = maxDifferenceRatio;
    }

    /**
     * Check if two sequences should be compared based on size.
     * 
     * @param seq1 First sequence
     * @param seq2 Second sequence
     * @return true if sequences should be compared, false if should be skipped
     */
    public boolean shouldCompare(StatementSequence seq1, StatementSequence seq2) {
        int size1 = seq1.statements().size();
        int size2 = seq2.statements().size();

        return shouldCompare(size1, size2);
    }

    /**
     * Check if two sequence sizes should be compared.
     * Useful for checking before creating full StatementSequence objects.
     * 
     * @param size1 Size of first sequence
     * @param size2 Size of second sequence
     * @return true if sequences should be compared, false if should be skipped
     */
    public boolean shouldCompare(int size1, int size2) {
        if (size1 == size2) {
            return true; // Same size always compare
        }

        int maxSize = Math.max(size1, size2);
        int minSize = Math.min(size1, size2);

        // Calculate size difference ratio
        double differenceRatio = (double) (maxSize - minSize) / maxSize;

        return differenceRatio <= maxDifferenceRatio;
    }

    /**
     * Get the configured max difference ratio.
     */
    public double getMaxDifferenceRatio() {
        return maxDifferenceRatio;
    }
}
