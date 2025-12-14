package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calculates structural similarity based on control flow patterns and method
 * calls.
 * Uses Jaccard similarity for fast comparison.
 */
public class StructuralSimilarity {

    /**
     * Calculate structural similarity between two token sequences.
     * Focuses on control flow, method calls, and structure.
     * 
     * @param tokens1 First token sequence
     * @param tokens2 Second token sequence
     * @return Similarity score (0.0 to 1.0)
     */
    public double calculate(List<Token> tokens1, List<Token> tokens2) {
        if (tokens1 == null || tokens2 == null) {
            return 0.0;
        }

        if (tokens1.isEmpty() && tokens2.isEmpty()) {
            return 1.0;
        }

        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }

        // Extract structural features
        Set<String> features1 = extractStructuralFeatures(tokens1);
        Set<String> features2 = extractStructuralFeatures(tokens2);

        // Calculate Jaccard similarity
        return jaccardSimilarity(features1, features2);
    }

    /**
     * Extract structural features from a token sequence.
     * Includes: control flow patterns, method calls, assertions, mocks.
     */
    private Set<String> extractStructuralFeatures(List<Token> tokens) {
        Set<String> features = new HashSet<>();

        for (Token token : tokens) {
            // Include structural elements
            switch (token.type()) {
                case CONTROL_FLOW:
                    // Add control flow pattern
                    features.add("CF:" + token.normalizedValue());
                    break;

                case METHOD_CALL:
                    // Add method call pattern
                    features.add("MC:" + token.normalizedValue());
                    break;

                case ASSERT:
                    // Add assertion pattern
                    features.add("ASSERT:" + token.normalizedValue());
                    break;

                case MOCK:
                    // Add mock pattern
                    features.add("MOCK:" + token.normalizedValue());
                    break;

                case TYPE:
                    // Add type reference
                    features.add("TYPE:" + token.normalizedValue());
                    break;

                case OPERATOR:
                    // Add operator (for structural complexity)
                    features.add("OP:" + token.normalizedValue());
                    break;

                default:
                    // Skip variables and literals (not structural)
                    break;
            }
        }

        return features;
    }

    /**
     * Calculate Jaccard similarity between two sets.
     * Jaccard = |A ∩ B| / |A ∪ B|
     */
    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }

        // Calculate intersection
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        // Calculate union
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }
}
