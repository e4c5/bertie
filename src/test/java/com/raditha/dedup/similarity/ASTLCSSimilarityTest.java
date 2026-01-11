package com.raditha.dedup.similarity;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTLCSSimilarityTest {

    private ASTLCSSimilarity similarity;
    private ASTNormalizer normalizer;

    @BeforeEach
    void setUp() {
        similarity = new ASTLCSSimilarity();
        normalizer = new ASTNormalizer();
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

        double score = similarity.calculate(norm1, norm2);

        // Should be 100% similar (same structure, different literals)
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testCompletelyDifferent() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");");
        List<Statement> stmts2 = parseStatements(
                "logger.info(\"Message\");");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        double score = similarity.calculate(norm1, norm2);

        // Should be 0% similar (completely different)
        assertEquals(0.0, score, 0.001);
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

        double score = similarity.calculate(norm1, norm2);

        // 2 out of 3 match
        assertEquals(2.0 / 3.0, score, 0.001);
    }

    @Test
    void testEmptySequences() {
        List<NormalizedNode> norm1 = normalizer.normalize(List.of());
        List<NormalizedNode> norm2 = normalizer.normalize(List.of());

        double score = similarity.calculate(norm1, norm2);

        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testOneEmpty() {
        List<Statement> stmts1 = parseStatements("user.setName(\"Alice\");");
        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(List.of());

        double score = similarity.calculate(norm1, norm2);

        assertEquals(0.0, score, 0.001);
    }

    @Test
    void testDifferentLengths() {
        List<Statement> stmts1 = parseStatements(
                "user.setName(\"Alice\");",
                "user.setAge(25);");
        List<Statement> stmts2 = parseStatements(
                "user.setName(\"Bob\");",
                "user.setAge(30);",
                "user.setActive(true);");

        List<NormalizedNode> norm1 = normalizer.normalize(stmts1);
        List<NormalizedNode> norm2 = normalizer.normalize(stmts2);

        double score = similarity.calculate(norm1, norm2);

        // 2 match out of max 3
        assertEquals(2.0 / 3.0, score, 0.001);
    }

    private List<Statement> parseStatements(String... statements) {
        return List.of(statements).stream()
                .map(StaticJavaParser::parseStatement)
                .toList();
    }
}
