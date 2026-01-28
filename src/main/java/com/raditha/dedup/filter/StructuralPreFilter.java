package com.raditha.dedup.filter;

import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import com.raditha.dedup.similarity.ASTStructuralSimilarity;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-filters sequence pairs using structural similarity (Jaccard on structural
 * features).
 * Eliminates ~50% of remaining comparisons after size filter.
 */
public class StructuralPreFilter {

    private final double minJaccardThreshold;
    private final ASTNormalizer normalizer;
    private final ASTStructuralSimilarity structuralSimilarity;

    /**
     * Cache for fuzzy normalization results to avoid repeated expensive normalization.
     * Uses IdentityHashMap because StatementSequence.equals() doesn't include the statements
     * list, so different sequences with same range/offset/path would collide in a regular HashMap.
     */
    private final Map<StatementSequence, List<NormalizedNode>> fuzzyNormCache = new IdentityHashMap<>();

    /**
     * Create filter with default 0.5 Jaccard threshold.
     */
    public StructuralPreFilter() {
        this(0.5);
    }

    /**
     * Create filter with custom Jaccard threshold.
     * 
     * @param minJaccardThreshold Minimum Jaccard similarity (0.0 to 1.0)
     */
    public StructuralPreFilter(double minJaccardThreshold) {
        if (minJaccardThreshold < 0.0 || minJaccardThreshold > 1.0) {
            throw new IllegalArgumentException("Jaccard threshold must be between 0.0 and 1.0");
        }
        this.minJaccardThreshold = minJaccardThreshold;
        this.normalizer = new ASTNormalizer();
        this.structuralSimilarity = new ASTStructuralSimilarity();
    }

    /**
     * Check if two sequences should be compared based on structural similarity.
     *
     * @param seq1 First sequence
     * @param seq2 Second sequence
     * @return true if sequences should be compared, false if should be skipped
     */
    public boolean shouldCompare(StatementSequence seq1, StatementSequence seq2) {
        // Normalize both sequences using cache to avoid repeated expensive normalization
        List<NormalizedNode> nodes1 = getCachedFuzzyNorm(seq1);
        List<NormalizedNode> nodes2 = getCachedFuzzyNorm(seq2);

        // Calculate structural similarity (Jaccard)
        double structuralScore = structuralSimilarity.calculate(nodes1, nodes2);

        // Only compare if structural similarity meets threshold
        return structuralScore >= minJaccardThreshold;
    }

    /**
     * Get fuzzy normalization for a sequence, using cache to avoid repeated computation.
     */
    private List<NormalizedNode> getCachedFuzzyNorm(StatementSequence seq) {
        return fuzzyNormCache.computeIfAbsent(seq, s -> normalizer.normalizeFuzzy(s.statements()));
    }

    /**
     * Clear the normalization cache. Call between analysis runs to free memory.
     */
    public void clearCache() {
        fuzzyNormCache.clear();
    }

    /**
     * Get current cache size (for diagnostics).
     */
    public int getCacheSize() {
        return fuzzyNormCache.size();
    }

    /**
     * Get the configured minimum Jaccard threshold.
     */
    public double getMinJaccardThreshold() {
        return minJaccardThreshold;
    }
}
