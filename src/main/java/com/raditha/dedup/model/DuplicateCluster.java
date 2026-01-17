package com.raditha.dedup.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
     * Get all distinct statement sequences in this cluster.
     * Includes primary and all sequences from similarity pairs.
     */
    public List<StatementSequence> allSequences() {
        List<StatementSequence> all = new ArrayList<>();
        all.add(primary);
        for (SimilarityPair pair : duplicates) {
            all.add(pair.seq1());
            all.add(pair.seq2());
        }
        // Return distinct sequences only
        return all.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Get unique methods containing the duplicates in this cluster.
     */
    public java.util.Set<com.github.javaparser.ast.body.MethodDeclaration> getContainingMethods() {
        return allSequences().stream()
                .map(StatementSequence::containingMethod)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
}
