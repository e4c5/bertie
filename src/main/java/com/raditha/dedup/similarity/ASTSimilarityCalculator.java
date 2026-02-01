package com.raditha.dedup.similarity;

import com.raditha.dedup.config.SimilarityWeights;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.normalization.NormalizedNode;

import java.util.List;

/**
 * Combines multiple AST-based similarity algorithms into a single score.
 * Uses configurable weights to balance different metrics.
 */
public class ASTSimilarityCalculator {

    private final ASTLCSSimilarity lcs;
    private final ASTLevenshteinSimilarity levenshtein;
    private final ASTStructuralSimilarity structural;

    /**
     * Initializes the calculator with default similarity component instances.
     */
    public ASTSimilarityCalculator() {
        this.lcs = new ASTLCSSimilarity();
        this.levenshtein = new ASTLevenshteinSimilarity();
        this.structural = new ASTStructuralSimilarity();
    }

    /**
     * Calculate overall similarity using all algorithms.
     * 
     * @param nodes1  First normalized sequence
     * @param nodes2  Second normalized sequence
     * @param weights Weights for combining scores
     * @return Combined similarity result
     */
    public SimilarityResult calculate(
            List<NormalizedNode> nodes1,
            List<NormalizedNode> nodes2,
            SimilarityWeights weights) {

        // Calculate individual scores
        double lcsScore = lcs.calculate(nodes1, nodes2);
        double levenshteinScore = levenshtein.calculate(nodes1, nodes2);
        double structuralScore = structural.calculate(nodes1, nodes2);

        // Combine using weights
        double overallScore = weights.combine(lcsScore, levenshteinScore, structuralScore);

        // For now, create a simplified SimilarityResult
        // We'll update this when integrating with the full system
        return new SimilarityResult(
                overallScore,
                lcsScore,
                levenshteinScore,
                structuralScore,
                nodes1.size(),
                nodes2.size(),
                com.raditha.dedup.model.VariationAnalysis.builder().build(), // variations - initialized empty
                null, // typeCompatibility - will be added later
                overallScore >= 0.70 // canRefactor threshold
        );
    }
}
