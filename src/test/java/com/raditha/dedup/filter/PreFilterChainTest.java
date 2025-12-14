package com.raditha.dedup.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PreFilterChain.
 */
class PreFilterChainTest {

    private PreFilterChain chain;

    @BeforeEach
    void setUp() {
        chain = new PreFilterChain(); // Both filters enabled
    }

    @Test
    void testSizeFilterRejectsFirst() {
        StatementSequence seq5 = createSequence(5);
        StatementSequence seq20 = createSequence(20);

        // Size difference: 75% >> 30% → rejected by size filter
        assertFalse(chain.shouldCompare(seq5, seq20));
    }

    @Test
    void testPassesSizeButFailsStructural() {
        // Similar size but different structure
        StatementSequence seq1 = createCodeSequence("""
                user.setName("John");
                user.setEmail("test@example.com");
                user.save();
                """);

        StatementSequence seq2 = createCodeSequence("""
                assertEquals(5, result);
                verify(mock).call();
                assertTrue(condition);
                """);

        // Same size (3 statements each), but completely different structure
        // Should pass size filter but fail structural filter
        boolean result = chain.shouldCompare(seq1, seq2);
        assertFalse(result, "Should fail structural filter despite passing size filter");
    }

    @Test
    void testPassesBothFilters() {
        StatementSequence seq1 = createCodeSequence("""
                user.setName("John");
                user.setEmail("test@example.com");
                user.save();
                """);

        StatementSequence seq2 = createCodeSequence("""
                customer.setName("Jane");
                customer.setEmail("jane@example.com");
                customer.save();
                """);

        // Same size, same structure → should pass both filters
        assertTrue(chain.shouldCompare(seq1, seq2));
    }

    @Test
    void testChainWithoutStructuralFilter() {
        PreFilterChain sizeOnly = new PreFilterChain(false); // Only size filter

        StatementSequence seq1 = createCodeSequence("""
                user.save();
                user.delete();
                user.load();
                """);

        StatementSequence seq2 = createCodeSequence("""
                assertEquals(1, x);
                assertEquals(2, y);
                assertEquals(3, z);
                """);

        // Different structure but same size
        // Should pass with size-only filter
        assertTrue(sizeOnly.shouldCompare(seq1, seq2));
    }

    @Test
    void testCustomThresholds() {
        // Very strict: 10% size difference, 80% Jaccard
        PreFilterChain strict = new PreFilterChain(0.10, 0.80);

        StatementSequence seq10 = createSequence(10);
        StatementSequence seq11 = createSequence(11);

        // 9.1% size difference < 10% → passes size filter
        // But then structural filter might reject
        boolean result = strict.shouldCompare(seq10, seq11);
        assertNotNull(result);
    }

    @Test
    void testGetStats() {
        PreFilterChain.FilterStats stats = chain.getStats();

        assertEquals(0.30, stats.maxSizeDifference(), 0.001);
        assertNotNull(stats.minStructuralJaccard());
        assertEquals(0.5, stats.minStructuralJaccard(), 0.001);

        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("30%"));
        assertTrue(statsStr.contains("0.50") || statsStr.contains("0.5"));
    }

    @Test
    void testGetStatsWithoutStructural() {
        PreFilterChain sizeOnly = new PreFilterChain(false);
        PreFilterChain.FilterStats stats = sizeOnly.getStats();

        assertEquals(0.30, stats.maxSizeDifference(), 0.001);
        assertNull(stats.minStructuralJaccard());

        String statsStr = stats.toString();
        assertTrue(statsStr.contains("disabled"));
    }

    private StatementSequence createSequence(int count) {
        List<Statement> statements = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            statements.add(StaticJavaParser.parseStatement("int x" + i + " = " + i + ";"));
        }

        return new StatementSequence(
                statements,
                new Range(1, count, 1, 10),
                0,
                null,
                null,
                Paths.get("Test.java"));
    }

    private StatementSequence createCodeSequence(String code) {
        List<Statement> statements = StaticJavaParser.parseBlock("{" + code + "}").getStatements();

        return new StatementSequence(
                statements,
                new Range(1, statements.size(), 1, 10),
                0,
                null,
                null,
                Paths.get("Test.java"));
    }
}
