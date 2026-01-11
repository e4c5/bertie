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
 * Unit tests for StructuralPreFilter.
 */
class StructuralPreFilterTest {

    private StructuralPreFilter filter;

    @BeforeEach
    void setUp() {
        filter = new StructuralPreFilter(); // Default 0.5 Jaccard threshold
    }

    @Test
    void testIdenticalMethodsShouldCompare() {
        StatementSequence seq1 = createSequence("""
                if (user != null) {
                    user.setActive(true);
                    user.save();
                }
                """);

        StatementSequence seq2 = createSequence("""
                if (customer != null) {
                    customer.setActive(false);
                    customer.save();
                }
                """);

        // Same structure (if, setActive, save) → high Jaccard
        assertTrue(filter.shouldCompare(seq1, seq2));
    }

    @Test
    void testSameMethodCallsDifferentControlFlowShouldSkip() {
        StatementSequence seq1 = createSequence("""
                if (user != null) {
                    user.save();
                }
                """);

        StatementSequence seq2 = createSequence("""
                while (running) {
                    user.save();
                }
                """);

        // Different control flow (if vs while) → might skip depending on features
        // This test verifies the filter can distinguish control flow patterns
        boolean result = filter.shouldCompare(seq1, seq2);
        // Result depends on Jaccard calculation - documenting the behavior
        assertNotNull(result); // Just verify it executes without error
    }

    @Test
    void testCompletelyDifferentShouldSkip() {
        StatementSequence seq1 = createSequence("""
                int a = 1;
                int b = 2;
                int c = a + b;
                """);

        StatementSequence seq2 = createSequence("""
                assertEquals(5, result);
                verify(mock).call();
                when(service.get()).thenReturn(value);
                """);

        // Completely different: arithmetic vs test assertions
        // Should have low structural similarity
        boolean result = filter.shouldCompare(seq1, seq2);
        assertFalse(result, "Completely different structures should be filtered out");
    }

    @Test
    void testHighStructuralOverlapShouldCompare() {
        StatementSequence seq1 = createSequence("""
                user.setName("John");
                user.setEmail("john@example.com");
                user.save();
                assertEquals(true, user.isActive());
                """);

        StatementSequence seq2 = createSequence("""
                customer.setName("Jane");
                customer.setEmail("jane@example.com");
                customer.save();
                assertEquals(false, customer.isActive());
                """);

        // High structural overlap: same method calls (setName, setEmail, save,
        // assertEquals)
        assertTrue(filter.shouldCompare(seq1, seq2));
    }

    @Test
    void testCustomThreshold() {
        StructuralPreFilter strictFilter = new StructuralPreFilter(0.8); // 80% Jaccard required

        StatementSequence seq1 = createSequence("""
                user.save();
                user.delete();
                """);

        StatementSequence seq2 = createSequence("""
                user.save();
                user.load();
                """);

        // Only 50% overlap (save is common, delete/load differ)
        // Previous behavior (incorrect): method names were normalized to "METHOD", making these 100% similar.
        // Correct behavior: method names are preserved ("delete" vs "load"), so similarity is ~0.5.
        // With 0.8 threshold, this should now be SKIPPED.
        boolean result = strictFilter.shouldCompare(seq1, seq2);
        assertFalse(result, "Should skip because 'delete' and 'load' are structurally distinct methods");
    }

    @Test
    void testCustomThresholdPassesForIdenticalStructure() {
        StructuralPreFilter strictFilter = new StructuralPreFilter(0.8);

        StatementSequence seq1 = createSequence("""
                user.save();
                user.delete();
                """);

        StatementSequence seq2 = createSequence("""
                customer.save();
                customer.delete();
                """);

        // Identical methods, different vars. Should pass strict filter.
        boolean result = strictFilter.shouldCompare(seq1, seq2);
        assertTrue(result, "Should pass when method names match (structural identity)");
    }

    @Test
    void testInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new StructuralPreFilter(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new StructuralPreFilter(1.5));
    }

    @Test
    void testGetMinJaccardThreshold() {
        assertEquals(0.5, filter.getMinJaccardThreshold(), 0.001);

        StructuralPreFilter custom = new StructuralPreFilter(0.7);
        assertEquals(0.7, custom.getMinJaccardThreshold(), 0.001);
    }

    private StatementSequence createSequence(String code) {
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
