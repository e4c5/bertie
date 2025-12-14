package com.raditha.dedup.similarity;

import com.raditha.dedup.config.SimilarityWeights;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimilarityCalculator.
 */
class SimilarityCalculatorTest {

    private SimilarityCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new SimilarityCalculator();
    }

    @Test
    void testCombinedCalculation() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        SimilarityWeights weights = SimilarityWeights.balanced();

        SimilarityResult result = calculator.calculate(tokens1, tokens2, weights);

        // Should have high overall similarity (identical normalized)
        assertTrue(result.overallScore() > 0.9);
        assertEquals(1.0, result.lcsScore(), 0.001);
        assertEquals(1.0, result.levenshteinScore(), 0.001);
    }

    @Test
    void testWeightedCombination() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete"));

        // All algorithms should return 0.0
        SimilarityWeights weights = SimilarityWeights.balanced();
        SimilarityResult result = calculator.calculate(tokens1, tokens2, weights);

        assertEquals(0.0, result.overallScore(), 0.001);
        assertEquals(0.0, result.lcsScore(), 0.001);
        assertEquals(0.0, result.levenshteinScore(), 0.001);
        assertEquals(0.0, result.structuralScore(), 0.001);
    }

    @Test
    void testRefactorabilityHighSimilarity() {
        List<Token> tokens = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        // High similarity, feasible variations & types
        VariationAnalysis variations = new VariationAnalysis(
                List.of(new Variation(VariationType.LITERAL, 0, 0, "123", "456", "int")),
                false);

        TypeCompatibility typeCompat = new TypeCompatibility(
                true,
                Map.of("param1", "int"),
                "void",
                List.of());

        SimilarityResult result = calculator.calculate(
                tokens,
                tokens,
                SimilarityWeights.balanced(),
                variations,
                typeCompat);

        assertTrue(result.canRefactor(), "High similarity with good variations should be refactorable");
    }

    @Test
    void testRefactorabilityLowSimilarity() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete"));

        VariationAnalysis variations = new VariationAnalysis(List.of(), false);
        TypeCompatibility typeCompat = new TypeCompatibility(true, Map.of(), null, List.of());

        SimilarityResult result = calculator.calculate(
                tokens1,
                tokens2,
                SimilarityWeights.balanced(),
                variations,
                typeCompat);

        assertFalse(result.canRefactor(), "Low similarity should not be refactorable");
    }

    @Test
    void testRefactorabilityControlFlowDifferences() {
        List<Token> tokens = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        // Control flow differences → not refactorable
        VariationAnalysis variations = new VariationAnalysis(List.of(), true);
        TypeCompatibility typeCompat = new TypeCompatibility(true, Map.of(), null, List.of());

        SimilarityResult result = calculator.calculate(
                tokens,
                tokens,
                SimilarityWeights.balanced(),
                variations,
                typeCompat);

        assertFalse(result.canRefactor(), "Control flow differences should prevent refactoring");
    }

    @Test
    void testSimplifiedCalculate() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "y"));

        SimilarityResult result = calculator.calculate(tokens1, tokens2, SimilarityWeights.balanced());

        assertNotNull(result);
        assertTrue(result.overallScore() >= 0.0 && result.overallScore() <= 1.0);
    }
}
