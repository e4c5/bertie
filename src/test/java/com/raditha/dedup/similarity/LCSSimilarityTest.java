package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LCSSimilarity.
 */
class LCSSimilarityTest {

    private LCSSimilarity similarity;

    @BeforeEach
    void setUp() {
        similarity = new LCSSimilarity();
    }

    @Test
    void testIdenticalSequences() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.VAR, "VAR", "y"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "a"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.VAR, "VAR", "b"));

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

        assertEquals(0.0, score, 0.001, "Completely different sequences should have 0% similarity");
    }

    @Test
    void testPartialMatch() {
        // Sequence 1: A B C D
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "a"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.VAR, "VAR", "c"),
                new Token(TokenType.VAR, "VAR", "d"));

        // Sequence 2: A B X Y
        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "a"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete"),
                new Token(TokenType.VAR, "VAR", "y"));

        double score = similarity.calculate(tokens1, tokens2);

        // LCS = 3 (VAR, METHOD_CALL(save)), max length = 4
        assertEquals(0.75, score, 0.001);
    }

    @Test
    void testDifferentLengths() {
        List<Token> tokens1 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.VAR, "VAR", "y"));

        List<Token> tokens2 = List.of(
                new Token(TokenType.VAR, "VAR", "x"),
                new Token(TokenType.METHOD_CALL, "METHOD_CALL(foo)", "foo"),
                new Token(TokenType.VAR, "VAR", "y"),
                new Token(TokenType.VAR, "VAR", "z"));

        double score = similarity.calculate(tokens1, tokens2);

        // LCS = 2 (x, y), max length = 4
        assertEquals(0.5, score, 0.001);
    }

    @Test
    void testEmptySequences() {
        double score = similarity.calculate(List.of(), List.of());
        assertEquals(1.0, score, 0.001, "Empty sequences should match 100%");
    }

    @Test
    void testOneEmpty() {
        List<Token> tokens = List.of(
                new Token(TokenType.VAR, "VAR", "x"));

        double score1 = similarity.calculate(tokens, List.of());
        double score2 = similarity.calculate(List.of(), tokens);

        assertEquals(0.0, score1, 0.001);
        assertEquals(0.0, score2, 0.001);
    }

    @Test
    void testNullSequences() {
        double score = similarity.calculate(null, null);
        assertEquals(0.0, score, 0.001);
    }
}
