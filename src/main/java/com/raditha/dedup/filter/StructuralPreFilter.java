package com.raditha.dedup.filter;

import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import com.raditha.dedup.similarity.ASTStructuralSimilarity;

import java.util.List;

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
        // Normalize both sequences to normalized nodes (using fuzzy/anonymized mode)
        List<NormalizedNode> nodes1 = normalizer.normalizeFuzzy(seq1.statements());
        List<NormalizedNode> nodes2 = normalizer.normalizeFuzzy(seq2.statements());

        // Calculate structural similarity (Jaccard)
        double structuralScore = structuralSimilarity.calculate(nodes1, nodes2);

        // Only compare if structural similarity meets threshold
        return structuralScore >= minJaccardThreshold;
    }

    /**
     * Get the configured minimum Jaccard threshold.
     */
    public double getMinJaccardThreshold() {
        return minJaccardThreshold;
    }
}
