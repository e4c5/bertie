package com.raditha.dedup.similarity;

import com.raditha.dedup.config.SimilarityWeights;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.TypeCompatibility;

import java.util.List;

/**
 * Combines multiple similarity algorithms into a single score.
 * Uses configurable weights to balance different metrics.
 */
public class SimilarityCalculator {

    private final LCSSimilarity lcs;
    private final LevenshteinSimilarity levenshtein;
    private final StructuralSimilarity structural;

    public SimilarityCalculator() {
        this.lcs = new LCSSimilarity();
        this.levenshtein = new LevenshteinSimilarity();
        this.structural = new StructuralSimilarity();
    }

    /**
     * Calculate overall similarity using all algorithms.
     * 
     * @param tokens1           First token sequence
     * @param tokens2           Second token sequence
     * @param weights           Weights for combining scores
     * @param variations        Variation analysis (from VariationTracker)
     * @param typeCompatibility Type compatibility analysis
     * @return Combined similarity result
     */
    public SimilarityResult calculate(
            List<Token> tokens1,
            List<Token> tokens2,
            SimilarityWeights weights,
            VariationAnalysis variations,
            TypeCompatibility typeCompatibility) {

        // Calculate individual scores
        double lcsScore = lcs.calculate(tokens1, tokens2);
        double levenshteinScore = levenshtein.calculate(tokens1, tokens2);
        double structuralScore = structural.calculate(tokens1, tokens2);

        // Combine using weights
        double overallScore = weights.combine(lcsScore, levenshteinScore, structuralScore);

        // Determine if refactoring is feasible
        boolean canRefactor = determineRefactorability(
                overallScore,
                variations,
                typeCompatibility);

        return new SimilarityResult(
                overallScore,
                lcsScore,
                levenshteinScore,
                structuralScore,
                tokens1.size(),
                tokens2.size(),
                variations,
                typeCompatibility,
                canRefactor);
    }

    /**
     * Simplified calculate method using default variation/type analysis.
     * Useful for quick similarity checks.
     */
    public SimilarityResult calculate(
            List<Token> tokens1,
            List<Token> tokens2,
            SimilarityWeights weights) {

        // Use empty/default analyses
        VariationAnalysis emptyVariations = new VariationAnalysis(List.of(), false);
        TypeCompatibility unknownType = new TypeCompatibility(
                false,
                java.util.Map.of(),
                null,
                List.of("Type analysis not performed"));

        return calculate(tokens1, tokens2, weights, emptyVariations, unknownType);
    }

    /**
     * Determine if sequences are refactorable based on similarity and analysis.
     * Note: Made more lenient to allow refactoring of moderately similar code.
     */
    private boolean determineRefactorability(
            double overallScore,
            VariationAnalysis variations,
            TypeCompatibility typeCompatibility) {

        // Lowered threshold from 0.75 to 0.70 for more permissive refactoring
        if (overallScore < 0.70) {
            return false;
        }

        // Check variation feasibility (but allow if variations are null/empty)
        if (variations != null && variations.hasControlFlowDifferences()) {
            return false; // Still reject control flow differences
        }

        // Be lenient with type compatibility - only reject if explicitly incompatible
        // (null or unknown types are OK)
        if (typeCompatibility != null && !typeCompatibility.isFeasible()) {
            // Only reject if there are actual type conflicts, not just unknowns
            if (typeCompatibility.isTypeSafe() == false) {
                return false;
            }
        }

        return true; // Default to allowing refactoring unless explicitly problematic
    }
}
