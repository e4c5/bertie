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
 * Unit tests for SizeFilter.
 */
class SizeFilterTest {

    private SizeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SizeFilter(); // Default 30% threshold
    }

    @Test
    void testSameSizeShouldCompare() {
        assertTrue(filter.shouldCompare(5, 5));
        assertTrue(filter.shouldCompare(10, 10));
        assertTrue(filter.shouldCompare(1, 1));
    }

    @Test
    void testWithin30PercentShouldCompare() {
        // 5 vs 6: difference = 1/6 = 16.7% < 30%
        assertTrue(filter.shouldCompare(5, 6));

        // 10 vs 12: difference = 2/12 = 16.7% < 30%
        assertTrue(filter.shouldCompare(10, 12));

        // 10 vs 13: difference = 3/13 = 23.1% < 30%
        assertTrue(filter.shouldCompare(10, 13));
    }

    @Test
    void testExactly30PercentShouldCompare() {
        // 7 vs 10: difference = 3/10 = 30.0% (exactly at threshold)
        assertTrue(filter.shouldCompare(7, 10));
    }

    @Test
    void testOver30PercentShouldSkip() {
        // 5 vs 10: difference = 5/10 = 50% > 30%
        assertFalse(filter.shouldCompare(5, 10));

        // 10 vs 15: difference = 5/15 = 33.3% > 30%
        assertFalse(filter.shouldCompare(10, 15));

        // 1 vs 10: difference = 9/10 = 90% > 30%
        assertFalse(filter.shouldCompare(1, 10));
    }

    @Test
    void testOrderDoesNotMatter() {
        // Should give same result regardless of order
        assertEquals(filter.shouldCompare(5, 10), filter.shouldCompare(10, 5));
        assertEquals(filter.shouldCompare(7, 10), filter.shouldCompare(10, 7));
        assertEquals(filter.shouldCompare(100, 130), filter.shouldCompare(130, 100));
    }

    @Test
    void testWithStatementSequences() {
        StatementSequence seq5 = createSequence(5);
        StatementSequence seq6 = createSequence(6);
        StatementSequence seq10 = createSequence(10);

        assertTrue(filter.shouldCompare(seq5, seq6)); // 16.7% < 30%
        assertFalse(filter.shouldCompare(seq5, seq10)); // 50% > 30%
    }

    @Test
    void testCustomThreshold() {
        SizeFilter strictFilter = new SizeFilter(0.10); // Only 10% difference allowed

        assertTrue(strictFilter.shouldCompare(10, 11)); // 9.1% < 10%
        assertFalse(strictFilter.shouldCompare(10, 12)); // 16.7% > 10%
    }

    @Test
    void testInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new SizeFilter(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new SizeFilter(1.5));
    }

    @Test
    void testGetMaxDifferenceRatio() {
        assertEquals(0.30, filter.getMaxDifferenceRatio(), 0.001);

        SizeFilter custom = new SizeFilter(0.25);
        assertEquals(0.25, custom.getMaxDifferenceRatio(), 0.001);
    }

    private StatementSequence createSequence(int statementCount) {
        List<Statement> statements = new java.util.ArrayList<>();
        for (int i = 0; i < statementCount; i++) {
            statements.add(StaticJavaParser.parseStatement("int x = " + i + ";"));
        }

        return new StatementSequence(
                statements,
                new Range(1, statementCount, 1, 10),
                0,
                null,
                null,
                Paths.get("Test.java"));
    }
}
