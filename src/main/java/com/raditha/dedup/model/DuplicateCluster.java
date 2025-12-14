package com.raditha.dedup.model;

import java.util.List;

/**
 * A cluster of duplicate code blocks.
 * Groups similar sequences together with a refactoring recommendation.
 * 
 * @param primary               Primary (representative) sequence for this
 *                              cluster
 * @param duplicates            All similar sequences in this cluster
 * @param recommendation        Refactoring recommendation for this cluster
 * @param estimatedLOCReduction Estimated lines of code reduction if refactored
 */
public record DuplicateCluster(
        StatementSequence primary,
        List<SimilarityPair> duplicates,
        RefactoringRecommendation recommendation,
        int estimatedLOCReduction) {
    /**
     * Get total number of duplicate instances (including primary).
     */
    public int getDuplicateCount() {
        return duplicates.size() + 1; // +1 for primary
    }

    /**
     * Get average similarity score across all duplicates.
     */
    public double getAverageSimilarity() {
        if (duplicates.isEmpty())
            return 1.0;
        return duplicates.stream()
                .mapToDouble(SimilarityPair::getScore)
                .average()
                .orElse(0.0);
    }

    /**
     * Get total duplicate lines across all instances.
     */
    public int getTotalDuplicateLines() {
        int primaryLines = primary.range().getLineCount();
        return primaryLines * getDuplicateCount();
    }

    /**
     * Format cluster summary for display.
     */
    public String formatSummary() {
        return String.format("%d occurrences, avg %.1f%% similar, ~%d LOC reduction",
                getDuplicateCount(),
                getAverageSimilarity() * 100,
                estimatedLOCReduction);
    }
}
