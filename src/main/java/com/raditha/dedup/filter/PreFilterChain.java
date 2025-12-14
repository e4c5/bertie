package com.raditha.dedup.filter;

import com.raditha.dedup.model.StatementSequence;

/**
 * Combines size and structural pre-filters for optimal performance.
 * Applies filters in order of cost: size first (cheap), then structural
 * (moderate).
 */
public class PreFilterChain {

    private final SizeFilter sizeFilter;
    private final StructuralPreFilter structuralFilter;
    private final boolean useStructuralFilter;

    /**
     * Create filter chain with both size and structural filters.
     */
    public PreFilterChain() {
        this(true);
    }

    /**
     * Create filter chain with optional structural filter.
     * 
     * @param useStructuralFilter Whether to use structural pre-filter (slower but
     *                            more accurate)
     */
    public PreFilterChain(boolean useStructuralFilter) {
        this.sizeFilter = new SizeFilter();
        this.structuralFilter = useStructuralFilter ? new StructuralPreFilter() : null;
        this.useStructuralFilter = useStructuralFilter;
    }

    /**
     * Create filter chain with custom thresholds.
     * 
     * @param maxSizeDiff Maximum size difference ratio (0.0 to 1.0)
     * @param minJaccard  Minimum structural Jaccard similarity (0.0 to 1.0)
     */
    public PreFilterChain(double maxSizeDiff, double minJaccard) {
        this.sizeFilter = new SizeFilter(maxSizeDiff);
        this.structuralFilter = new StructuralPreFilter(minJaccard);
        this.useStructuralFilter = true;
    }

    /**
     * Check if two sequences should be compared.
     * Applies filters in order: size first, then structural (if enabled).
     * 
     * @param seq1 First sequence
     * @param seq2 Second sequence
     * @return true if sequences should be compared, false if should be skipped
     */
    public boolean shouldCompare(StatementSequence seq1, StatementSequence seq2) {
        // Stage 1: Size filter (fast, O(1))
        if (!sizeFilter.shouldCompare(seq1, seq2)) {
            return false; // Skip: size difference too large
        }

        // Stage 2: Structural filter (moderate cost, if enabled)
        if (useStructuralFilter) {
            if (!structuralFilter.shouldCompare(seq1, seq2)) {
                return false; // Skip: structural dissimilarity
            }
        }

        // Passed all filters
        return true;
    }

    /**
     * Get statistics about filter configuration.
     */
    public FilterStats getStats() {
        return new FilterStats(
                sizeFilter.getMaxDifferenceRatio(),
                useStructuralFilter ? structuralFilter.getMinJaccardThreshold() : null);
    }

    /**
     * Statistics record for filter configuration.
     */
    public record FilterStats(
            double maxSizeDifference,
            Double minStructuralJaccard) {
        @Override
        public String toString() {
            if (minStructuralJaccard != null) {
                return String.format("Size: %.0f%%, Structural Jaccard: %.2f",
                        maxSizeDifference * 100, minStructuralJaccard);
            } else {
                return String.format("Size: %.0f%% (structural filter disabled)",
                        maxSizeDifference * 100);
            }
        }
    }
}
