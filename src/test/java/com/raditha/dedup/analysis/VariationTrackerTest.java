package com.raditha.dedup.analysis;

import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VariationTracker.
 * Verifies LCS alignment and variation detection.
 * 
 * NOTE: Tests compare ALREADY NORMALIZED tokens. Variations are detected when
 * the normalizedValue differs, not when originalValue differs.
 */
class VariationTrackerTest {

        private VariationTracker tracker;

        @BeforeEach
        void setUp() {
                tracker = new VariationTracker();
        }

        @Test
        void testExactMatch_NoVariations() {
                // Semantically identical normalized tokens
                List<Token> tokens1 = List.of(
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.VAR, "VAR", "data"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.VAR, "VAR", "customer"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.VAR, "VAR", "info"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // All normalized values match → no variations
                assertEquals(0, analysis.getVariationCount(),
                                "Semantically identical sequences should have no variations");
                assertFalse(analysis.hasControlFlowDifferences());
        }

        @Test
        void testMethodCallVariation() {
                // DIFFERENT normalized method names
                List<Token> tokens1 = List.of(
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setActive)", "setActive"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setDeleted)", "setDeleted"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Method names differ in normalizedValue → variation detected
                assertEquals(1, analysis.getVariationCount());
                assertEquals(1, analysis.getMethodCallVariations().size());

                Variation var = analysis.getMethodCallVariations().get(0);
                assertEquals(VariationType.METHOD_CALL, var.type());
                assertEquals("setActive", var.value1());
                assertEquals("setDeleted", var.value2());
        }

        @Test
        void testTypeVariation() {
                // DIFFERENT normalized type names
                List<Token> tokens1 = List.of(
                                new Token(TokenType.TYPE, "TYPE(User)", "User"),
                                new Token(TokenType.VAR, "VAR", "obj"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.TYPE, "TYPE(Customer)", "Customer"),
                                new Token(TokenType.VAR, "VAR", "obj"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                assertEquals(1, analysis.getVariationCount());
                assertEquals(1, analysis.getTypeVariations().size());

                Variation var = analysis.getTypeVariations().get(0);
                assertEquals(VariationType.TYPE, var.type());
                assertEquals("User", var.value1());
                assertEquals("Customer", var.value2());
        }

        @Test
        void testControlFlowDifference() {
                // DIFFERENT control flow keywords
                List<Token> tokens1 = List.of(
                                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"),
                                new Token(TokenType.VAR, "VAR", "x"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(while)", "while"),
                                new Token(TokenType.VAR, "VAR", "x"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                assertTrue(analysis.hasControlFlowDifferences(),
                                "Different control flow keywords should be detected");

                // Should have a CONTROL_FLOW variation
                List<Variation> controlFlowVars = analysis.getVariationsOfType(VariationType.CONTROL_FLOW);
                assertEquals(1, controlFlowVars.size());
        }

        @Test
        void testAlignmentWithGaps() {
                // Sequence 1: VAR -> METHOD_CALL -> VAR
                List<Token> tokens1 = List.of(
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.VAR, "VAR", "result"));

                // Sequence 2: VAR -> METHOD_CALL -> KEYWORD (extra token) -> VAR
                List<Token> tokens2 = List.of(
                                new Token(TokenType.VAR, "VAR", "customer"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.KEYWORD, "KEYWORD(return)", "return"),
                                new Token(TokenType.VAR, "VAR", "result"));

                // Should handle gap gracefully via LCS alignment
                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // The KEYWORD gap doesn't create a tracked variation (we skip keywords in
                // categorization)
                // LCS should align: VAR-VAR, METHOD_CALL-METHOD_CALL, VAR-VAR
                assertFalse(analysis.hasControlFlowDifferences());
        }

        @Test
        void testMultipleVariations() {
                // Complex case with ACTUAL differences in normalized values
                List<Token> tokens1 = List.of(
                                new Token(TokenType.TYPE, "TYPE(User)", "User"),
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setActive)", "setActive"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.TYPE, "TYPE(Customer)", "Customer"),
                                new Token(TokenType.VAR, "VAR", "customer"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setStatus)", "setStatus"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Should detect: TYPE variation + METHOD_CALL variation
                // (VAR matches since both have "VAR" as normalizedValue)
                assertTrue(analysis.hasVariations());
                assertEquals(2, analysis.getVariationCount());
                assertEquals(1, analysis.getTypeVariations().size());
                assertEquals(1, analysis.getMethodCallVariations().size());
        }

        @Test
        void testLiteralsAreNormalized() {
                // Literals with SAME type but different values normalize identically
                List<Token> tokens1 = List.of(
                                new Token(TokenType.STRING_LIT, "STRING_LIT", "\"PENDING\""));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.STRING_LIT, "STRING_LIT", "\"APPROVED\""));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Both normalize to STRING_LIT → no variation detected
                assertEquals(0, analysis.getVariationCount(),
                                "Normalized string literals should match semantically");
        }

        @Test
        void testCanParameterize() {
                // Simple variation that can be parameterized
                List<Token> tokens1 = List.of(
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setId)", "setId"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(setName)", "setName"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Only 1 variation (method call), no control flow differences
                assertFalse(analysis.hasControlFlowDifferences());
                assertEquals(1, analysis.getVariationCount());
                assertTrue(analysis.getVariationCount() <= 5);
        }

        @Test
        void testEmptySequences() {
                VariationAnalysis analysis = tracker.trackVariations(List.of(), List.of());

                assertEquals(0, analysis.getVariationCount());
                assertFalse(analysis.hasControlFlowDifferences());
        }

        @Test
        void testNullSequences() {
                VariationAnalysis analysis = tracker.trackVariations(null, null);

                assertEquals(0, analysis.getVariationCount());
                assertFalse(analysis.hasControlFlowDifferences());
        }
}
