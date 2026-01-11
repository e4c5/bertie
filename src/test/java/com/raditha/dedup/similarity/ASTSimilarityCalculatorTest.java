package com.raditha.dedup.similarity;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.config.SimilarityWeights;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTSimilarityCalculatorTest {

    private ASTSimilarityCalculator calculator;
    private ASTNormalizer normalizer;
    private SimilarityWeights defaultWeights;

    @BeforeEach
    void setUp() {
        calculator = new ASTSimilarityCalculator();
        normalizer = new ASTNormalizer();
        defaultWeights = SimilarityWeights.balanced();
    }

    @Test
    void testIdenticalSequences() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");",
                "user.setAge(25);");
        List<Statement> stmts2 = parseStatements(
                "user.setName(\"Bob\");",
                "user.setAge(30);");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        SimilarityResult result = calculator.calculate(norm1, norm2, defaultWeights);

        // All metrics should be 1.0
        assertEquals(1.0, result.lcsScore(), 0.001);
        assertEquals(1.0, result.levenshteinScore(), 0.001);
        assertEquals(1.0, result.structuralScore(), 0.001);
        assertEquals(1.0, result.overallScore(), 0.001);
        assertTrue(result.canRefactor());
    }

    @Test
    void testCompletelyDifferent() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");");
        List<Statement> stmts2 = parseStatements(
                "logger.info(\"Message\");");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        SimilarityResult result = calculator.calculate(norm1, norm2, defaultWeights);

        // All metrics should be 0.0
        assertEquals(0.0, result.lcsScore(), 0.001);
        assertEquals(0.0, result.levenshteinScore(), 0.001);
        assertEquals(0.0, result.structuralScore(), 0.001);
        assertEquals(0.0, result.overallScore(), 0.001);
        assertFalse(result.canRefactor());
    }

    @Test
    void testPartiallySimilar() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");",
                "user.setAge(25);",
                "user.setActive(true);");
        List<Statement> stmts2 = parseStatements(
                "user.setName(\"Bob\");",
                "user.setAge(30);",
                "logger.info(\"Done\");");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        SimilarityResult result = calculator.calculate(norm1, norm2, defaultWeights);

        // Should have moderate similarity (2/3 match)
        assertTrue(result.overallScore() > 0.5);
        assertTrue(result.overallScore() < 1.0);
        // 2/3 = 0.666... which is below 0.70 threshold
        assertFalse(result.canRefactor());
    }

    @Test
    void testBelowThreshold() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");",
                "user.setAge(25);",
                "user.setActive(true);");
        List<Statement> stmts2 = parseStatements(
                "logger.info(\"Start\");",
                "logger.info(\"End\");",
                "logger.info(\"Done\");");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        SimilarityResult result = calculator.calculate(norm1, norm2, defaultWeights);

        // Should be below refactoring threshold
        assertTrue(result.overallScore() < 0.70);
        assertFalse(result.canRefactor());
    }

    private List<Statement> parseStatements(String... statements) {
        return List.of(statements).stream()
                .map(StaticJavaParser::parseStatement)
                .toList();
    }
}
