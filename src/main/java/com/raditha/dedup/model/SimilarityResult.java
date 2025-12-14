package com.raditha.dedup.model;

/**
 * Result of comparing two statement sequences for similarity.
 * Combines multiple similarity metrics and variation analysis.
 * 
 * @param overallScore      Combined similarity score (0.0-1.0)
 * @param lcsScore          LCS similarity score (0.0-1.0)
 * @param levenshteinScore  Levenshtein similarity score (0.0-1.0)
 * @param structuralScore   Structural similarity score (0.0-1.0)
 * @param tokens1Count      Number of tokens in first sequence
 * @param tokens2Count      Number of tokens in second sequence
 * @param variations        Analysis of differences between sequences
 * @param typeCompatibility Type compatibility analysis
 * @param canRefactor       True if auto-refactoring is feasible
 */
public record SimilarityResult(
        double overallScore,
        double lcsScore,
        double levenshteinScore,
        double structuralScore,
        int tokens1Count,
        int tokens2Count,
        VariationAnalysis variations,
        TypeCompatibility typeCompatibility,
        boolean canRefactor) {
    /**
     * Check if similarity exceeds a threshold.
     */
    public boolean exceedsThreshold(double threshold) {
        return overallScore >= threshold;
    }

    /**
     * Get similarity percentage (0-100).
     */
    public double getSimilarityPercentage() {
        return overallScore * 100.0;
    }

    /**
     * Format score as percentage string.
     */
    public String formatScore() {
        return String.format("%.1f%%", getSimilarityPercentage());
    }

    /**
     * Check if this is a high-confidence duplicate (>= 90%).
     */
    public boolean isHighConfidence() {
        return overallScore >= 0.90;
    }

    /**
     * Check if this is a moderate duplicate (70-90%).
     */
    public boolean isModerateConfidence() {
        return overallScore >= 0.70 && overallScore < 0.90;
    }
}
