package com.raditha.dedup.analysis;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import com.raditha.dedup.model.StatementSequence;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for VariationTracker.
 * Verifies LCS alignment and variation detection.
 * <p>
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
        void testVariableRenaming_Detected() {
                // Same structure (VAR, CALL, VAR) but different variable names
                List<Token> tokens1 = List.of(
                                new Token(TokenType.VAR, "VAR", "user"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.VAR, "VAR", "data"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.VAR, "VAR", "customer"),
                                new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save"),
                                new Token(TokenType.VAR, "VAR", "info"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Should find 2 variations (user->customer, data->info)
                assertEquals(2, analysis.getVariationCount(),
                                "Renamed variables should be detected as variations");
                assertEquals(VariationType.VARIABLE, analysis.variations().get(0).type());
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

                Variation variable = analysis.getMethodCallVariations().get(0);
                assertEquals(VariationType.METHOD_CALL, variable.type());
                assertEquals("setActive", variable.value1());
                assertEquals("setDeleted", variable.value2());
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

                Variation variable = analysis.getTypeVariations().get(0);
                assertEquals(VariationType.TYPE, variable.type());
                assertEquals("User", variable.value1());
                assertEquals("Customer", variable.value2());
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

                // Should detect: TYPE variation + VARIABLE variation + METHOD_CALL variation
                assertTrue(analysis.hasVariations());
                assertEquals(3, analysis.getVariationCount());
                assertEquals(1, analysis.getTypeVariations().size());
                assertEquals(1, analysis.getVariableVariations().size());
                assertEquals(1, analysis.getMethodCallVariations().size());
        }

        @Test
        void testLiteralsAreNormalized() {
                // UPDATED: After fix, literals with different values create variations
                List<Token> tokens1 = List.of(
                                new Token(TokenType.STRING_LIT, "STRING_LIT", "\"PENDING\""));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.STRING_LIT, "STRING_LIT", "\"APPROVED\""));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // Different values → variation IS detected after semanticallyMatches() fix
                assertEquals(1, analysis.getVariationCount(),
                                "Different string literals should create a variation");
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

        @Test
        void testGapsAtStartAndEnd() {
                // LCS Alignment test: Sequence 2 has extra tokens at start and end
                // Seq 1: [MATCH]
                // Seq 2: [GAP] [MATCH] [GAP]
                // Use tokens with DISTINCT NORMALIZED VALUES so LCS aligns them correctly

                List<Token> tokens1 = List.of(
                                new Token(TokenType.METHOD_CALL, "METHOD(middle)", "middle"));

                List<Token> tokens2 = List.of(
                                new Token(TokenType.METHOD_CALL, "METHOD(start)", "start"),
                                new Token(TokenType.METHOD_CALL, "METHOD(middle)", "middle"),
                                new Token(TokenType.METHOD_CALL, "METHOD(end)", "end"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                // The middle token matches.
                // The start and end tokens in tokens2 do NOT create variations.
                // Since they are METHOD_CALL tokens (not control flow), they are ignored as
                // gaps.
                // Variation count should be 0.
                assertEquals(0, analysis.getVariationCount());
                assertFalse(analysis.hasControlFlowDifferences());
        }

        @Test
        void testDifferentLiteralTypes() {
                // Boolean diff
                List<Token> tokens1 = List.of(
                                new Token(TokenType.BOOLEAN_LIT, "BOOLEAN_LIT", "true"));
                List<Token> tokens2 = List.of(
                                new Token(TokenType.BOOLEAN_LIT, "BOOLEAN_LIT", "false"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);
                assertEquals(1, analysis.getVariationCount());
                assertEquals(VariationType.LITERAL, analysis.variations().get(0).type());

                // Integer diff
                tokens1 = List.of(new Token(TokenType.INT_LIT, "INT_LIT", "100"));
                tokens2 = List.of(new Token(TokenType.INT_LIT, "INT_LIT", "200"));
                analysis = tracker.trackVariations(tokens1, tokens2);
                assertEquals(1, analysis.getVariationCount());
                assertEquals(VariationType.LITERAL, analysis.variations().get(0).type());

                // Null diff? (NULL_LIT uses "null" as value usually)
                tokens1 = List.of(new Token(TokenType.NULL_LIT, "NULL_LIT", "null"));
                tokens2 = List.of(new Token(TokenType.NULL_LIT, "NULL_LIT", "null"));
                // Same value -> 0
                assertEquals(0, tracker.trackVariations(tokens1, tokens2).getVariationCount());
        }

        @Test
        void testMixedTokenTypes_Ignored() {
                // One is VAR, one is STRING_LIT.
                // categorizeVariation returns null for mixed types (unless one is Control Flow)
                List<Token> tokens1 = List.of(
                                new Token(TokenType.VAR, "VAR", "x"));
                List<Token> tokens2 = List.of(
                                new Token(TokenType.STRING_LIT, "STRING_LIT", "\"x\""));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, tokens2);

                assertEquals(0, analysis.getVariationCount(), "Mixed types should be ignored");
        }

        @Test
        void testOneSequenceEmpty() {
                List<Token> tokens = List.of(new Token(TokenType.VAR, "VAR", "x"));

                // Case 1: tokens1 empty
                VariationAnalysis analysis1 = tracker.trackVariations(List.of(), tokens);
                assertEquals(0, analysis1.getVariationCount()); // Gaps ignored

                // Case 2: tokens2 empty
                VariationAnalysis analysis2 = tracker.trackVariations(tokens, List.of());
                assertEquals(0, analysis2.getVariationCount()); // Gaps ignored
        }

        @Test
        void testValueBindingsPopulation() {
                // Create dummy StatementSequence objects (mocked or minimal)
                // Since StatementSequence is a record, we can just instantiate it with
                // nulls/empty vars
                // as long as we don't access fields that trigger NPEs in the test.
                // However, the map uses StatementSequence as a key, so they must be distinct
                // objects.

                StatementSequence seq1 = new StatementSequence(
                                List.of(), null, 0, null, null, Path.of("file1.java"));
                StatementSequence seq2 = new StatementSequence(
                                List.of(), null, 0, null, null, Path.of("file2.java"));

                List<Token> tokens1 = List.of(new Token(TokenType.VAR, "VAR", "user"));
                List<Token> tokens2 = List.of(new Token(TokenType.VAR, "VAR", "customer"));

                VariationAnalysis analysis = tracker.trackVariations(tokens1, seq1, tokens2, seq2);

                assertEquals(1, analysis.getVariationCount());

                Map<Integer, Map<StatementSequence, String>> bindings = analysis.valueBindings();
                assertNotNull(bindings);
                assertFalse(bindings.isEmpty());

                Map<StatementSequence, String> var0Bindings = bindings.get(0);
                assertNotNull(var0Bindings);
                assertEquals("user", var0Bindings.get(seq1));
                assertEquals("customer", var0Bindings.get(seq2));
        }
}
