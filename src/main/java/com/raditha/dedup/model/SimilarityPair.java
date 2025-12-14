package com.raditha.dedup.model;

/**
 * A pair of sequences with their similarity result.
 * Used during clustering to track related duplicates.
 * 
 * @param seq1       First statement sequence
 * @param seq2       Second statement sequence
 * @param similarity Similarity analysis result
 */
public record SimilarityPair(
        StatementSequence seq1,
        StatementSequence seq2,
        SimilarityResult similarity) {
    /**
     * Get similarity score.
     */
    public double getScore() {
        return similarity.overallScore();
    }

    /**
     * Check if similarity exceeds threshold.
     */
    public boolean exceedsThreshold(double threshold) {
        return similarity.exceedsThreshold(threshold);
    }
}
