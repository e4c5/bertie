package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LevenshteinSimilarity.
 */
class LevenshteinSimilarityTest {

    private LevenshteinSimilarity similarity;

    @BeforeEach
    void setUp() {
        similarity = new LevenshteinSimilarity();
    }

    @Test
    void testIdenticalSequences() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        double score = similarity.calculate(tokens1, tokens2);

        assertEquals(1.0, score, 0.001, "Identical normalized sequences should have 100% similarity");
    }

    @Test
    void testCompletelyDifferent() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete"));

        double score = similarity.calculate(tokens1, tokens2);

        // Distance = 1, max length = 1, similarity = 1 - 1/1 = 0
        assertEquals(0.0, score, 0.001);
    }

    @Test
    void testOneSubstitution() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.VAR, "VAR", "z"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(foo)", "foo"), // Different
                new Token(TokenType.VAR, "VAR", "z"));

        double score = similarity.calculate(tokens1, tokens2);

        // Distance = 1, max length = 3, similarity = 1 - 1/3 = 0.666...
        assertEquals(0.666, score, 0.01);
    }

    @Test
    void testOneDeletion() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.VAR, "VAR", "z"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.VAR, "VAR", "z"));

        double score = similarity.calculate(tokens1, tokens2);

        // Distance = 1, max length = 3, similarity = 1 - 1/3 = 0.666...
        assertEquals(0.666, score, 0.01);
    }

    @Test
    void testEmptySequences() {
        double score = similarity.calculate(List.of(), List.of());
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testOneEmpty() {
        List<Token> tokens = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.VAR, "VAR", "y"));

        double score = similarity.calculate(tokens, List.of());

        // Distance = 2, max length = 2, similarity = 1 - 2/2 = 0
        assertEquals(0.0, score, 0.001);
    }

    @Test
    void testNullSequences() {
        double score = similarity.calculate(null, null);
        assertEquals(0.0, score, 0.001);
    }
}
