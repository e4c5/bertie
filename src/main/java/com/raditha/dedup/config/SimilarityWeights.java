package com.raditha.dedup.config;

/**
 * Weights for combining similarity metrics.
 * Used to calculate overall similarity score from LCS, Levenshtein, and
 * structural scores.
 * 
 * @param lcsWeight         Weight for LCS score (0.0-1.0)
 * @param levenshteinWeight Weight for Levenshtein score (0.0-1.0)
 * @param structuralWeight  Weight for structural score (0.0-1.0)
 */
public record SimilarityWeights(
        double lcsWeight,
        double levenshteinWeight,
        double structuralWeight) {
    /**
     * Validate weights sum to 1.0.
     */
    public SimilarityWeights {
        double sum = lcsWeight + levenshteinWeight + structuralWeight;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException(
                    String.format("Weights must sum to 1.0, got %.3f", sum));
        }
    }

    /**
     * Balanced weights (default): prioritizes token sequences over structure.
     */
    public static SimilarityWeights balanced() {
        return new SimilarityWeights(0.40, 0.40, 0.20);
    }

    /**
     * Strict weights: emphasizes exact matches.
     */
    public static SimilarityWeights strict() {
        return new SimilarityWeights(0.35, 0.50, 0.15);
    }

    /**
     * Structural weights: emphasizes control flow patterns.
     */
    public static SimilarityWeights structural() {
        return new SimilarityWeights(0.35, 0.30, 0.35);
    }

    /**
     * Lenient weights: more tolerant to gaps.
     */
    public static SimilarityWeights lenient() {
        return new SimilarityWeights(0.45, 0.35, 0.20);
    }

    /**
     * Calculate combined score from individual metrics.
     */
    public double combine(double lcs, double levenshtein, double structural) {
        return (lcs * lcsWeight) +
                (levenshtein * levenshteinWeight) +
                (structural * structuralWeight);
    }
}
