package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StructuralSimilarity.
 */
class StructuralSimilarityTest {

    private StructuralSimilarity similarity;

    @BeforeEach
    void setUp() {
        similarity = new StructuralSimilarity();
    }

    @Test
    void testIdenticalStructure() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.ASSERT, "ASSERT(assertEquals)", "assertEquals"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.ASSERT, "ASSERT(assertEquals)", "assertEquals"));

        double score = similarity.calculate(tokens1, tokens2);

        assertEquals(1.0, score, 0.001, "Identical structures should match 100%");
    }

    @Test
    void testDifferentControlFlow() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                new Token(TokenType.VAR, "VAR", "x"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(while)", "while"),
                new Token(TokenType.VAR, "VAR", "x"));

        double score = similarity.calculate(tokens1, tokens2);

        // Different control flow → Jaccard = 0/2 = 0.0
        // (VAR is ignored in structural features)
        assertEquals(0.0, score, 0.001);
    }

    @Test
    void testPartialOverlap() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.VAR, "VAR", "x"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete"),
                new Token(TokenType.VAR, "VAR", "y"));

        double score = similarity.calculate(tokens1, tokens2);

        // Features1: {CF:if, MC:save}
        // Features2: {CF:if, MC:delete}
        // Intersection: {CF:if} = 1
        // Union: {CF:if, MC:save, MC:delete} = 3
        // Jaccard = 1/3 = 0.333...
        assertEquals(0.333, score, 0.01);
    }

    @Test
    void testIgnoresVariablesAndLiterals() {
        // Only structural elements matter
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.STRING_LIT, "STRING_LIT", "abc"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(foo)", "foo"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.STRING_LIT, "STRING_LIT", "xyz"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(foo)", "foo"));

        double score = similarity.calculate(tokens1, tokens2);

        // Only MC:foo is structural, appearing in both
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testEmptySequences() {
        double score = similarity.calculate(List.of(), List.of());
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testOneEmpty() {
        List<Token> tokens = List.of(
                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"));

        double score = similarity.calculate(tokens, List.of());
        assertEquals(0.0, score, 0.001);
    }

    @Test
    void testNullSequences() {
        double score = similarity.calculate(null, null);
        assertEquals(0.0, score, 0.001);
    }
}
