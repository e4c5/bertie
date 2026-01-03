package com.raditha.dedup.analysis;

import com.raditha.dedup.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks variations (differences) between two similar code sequences.
 * Uses positional alignment for same-length sequences, LCS for different
 * lengths.
 */
public class VariationTracker {

    /**
     * Track all variations between two token sequences.
     */
    public VariationAnalysis trackVariations(List<Token> tokens1, List<Token> tokens2) {
        return trackVariations(tokens1, null, tokens2, null);
    }

    /**
     * Track all variations between two token sequences, capturing actual values
     * from
     * the source statements.
     */
    public VariationAnalysis trackVariations(
            List<Token> tokens1, StatementSequence seq1,
            List<Token> tokens2, StatementSequence seq2) {

        if (tokens1 == null || tokens2 == null) {
            return new VariationAnalysis(
                    List.of(),
                    false,
                    new java.util.HashMap<>(),
                    java.util.Collections.emptyList(),
                    new java.util.HashMap<>());
        }

        if (tokens1.isEmpty() && tokens2.isEmpty()) {
            return new VariationAnalysis(
                    List.of(),
                    false,
                    new java.util.HashMap<>(),
                    java.util.Collections.emptyList(),
                    new java.util.HashMap<>());
        }

        // Find all variations
        List<Variation> variations = new ArrayList<>();
        Map<Integer, Map<StatementSequence, String>> valueBindings = new HashMap<>();
        Map<Integer, Map<StatementSequence, com.raditha.dedup.model.ExprInfo>> exprBindings = new HashMap<>();
        boolean hasControlFlowDifferences = false;

        // For simplicity in Phase 1: use positional alignment if same length
        // For different lengths: use LCS alignment
        if (tokens1.size() == tokens2.size()) {
            // Positional alignment - compare token by token
            for (int i = 0; i < tokens1.size(); i++) {
                Token t1 = tokens1.get(i);
                Token t2 = tokens2.get(i);

                boolean semanticallyMatches = t1.semanticallyMatches(t2);
                boolean isVariableMismatch = semanticallyMatches && t1.type() == TokenType.VAR
                        && !t1.originalValue().equals(t2.originalValue());

                if (!semanticallyMatches || isVariableMismatch) {
                    Variation variation = createVariation(i, i, t1, t2);
                    if (variation != null) {
                        variations.add(variation);
                        // Key value bindings by aligned source position to avoid index drift
                        captureValueBindings(valueBindings, exprBindings, variation.alignedIndex1(), variation, seq1, t1, seq2, t2);

                        if (variation.type() == VariationType.CONTROL_FLOW) {
                            hasControlFlowDifferences = true;
                        }
                    }
                }
            }
        } else {
            // Use LCS alignment for different-length sequences
            List<TokenAlignment> alignments = computeLCSAlignment(tokens1, tokens2);

            for (TokenAlignment alignment : alignments) {
                if (alignment.token1() != null && alignment.token2() != null) {
                    // Both tokens exist - check if they differ
                    boolean semanticallyMatches = alignment.token1().semanticallyMatches(alignment.token2());
                    boolean isVariableMismatch = semanticallyMatches && alignment.token1().type() == TokenType.VAR
                            && !alignment.token1().originalValue().equals(alignment.token2().originalValue());

                    if (!semanticallyMatches || isVariableMismatch) {
                        Variation variation = createVariation(
                                alignment.index1(),
                                alignment.index2(),
                                alignment.token1(),
                                alignment.token2());
                        if (variation != null) {
                            variations.add(variation);
                            // Key value bindings by aligned source position to avoid index drift
                            captureValueBindings(valueBindings, exprBindings, variation.alignedIndex1(), variation, seq1, alignment.token1(), seq2,
                                    alignment.token2());

                            if (variation.type() == VariationType.CONTROL_FLOW) {
                                hasControlFlowDifferences = true;
                            }
                        }
                    }
                } else {
                    // Gap in one sequence - potential control flow difference
                    if (alignment.token1() != null && alignment.token1().type() == TokenType.CONTROL_FLOW) {
                        hasControlFlowDifferences = true;
                    } else if (alignment.token2() != null && alignment.token2().type() == TokenType.CONTROL_FLOW) {
                        hasControlFlowDifferences = true;
                    }
                }
            }
        }

        return new VariationAnalysis(variations, hasControlFlowDifferences, valueBindings, tokens1, exprBindings);
    }

    private void captureValueBindings(
            Map<Integer, Map<StatementSequence, String>> valueBindings,
            Map<Integer, Map<StatementSequence, com.raditha.dedup.model.ExprInfo>> exprBindings,
            int paramIndex,
            Variation variation,
            StatementSequence seq1, Token t1,
            StatementSequence seq2, Token t2) {

        // Only capture bindings if we have the sequence context
        if (seq1 == null || seq2 == null) {
            return;
        }

        // Use the caller-provided variation index to key the bindings maps.
        // Legacy string bindings (for backward compatibility)
        Map<StatementSequence, String> strBinding = new HashMap<>();
        strBinding.put(seq1, t1.originalValue());
        strBinding.put(seq2, t2.originalValue());
        valueBindings.put(paramIndex, strBinding);

        // New AST-first bindings (ExprInfo) — currently text-backed; later we can attach real nodes
        Map<StatementSequence, com.raditha.dedup.model.ExprInfo> exprBinding = new HashMap<>();
        exprBinding.put(seq1, com.raditha.dedup.model.ExprInfo.fromText(t1.originalValue()));
        exprBinding.put(seq2, com.raditha.dedup.model.ExprInfo.fromText(t2.originalValue()));
        exprBindings.put(paramIndex, exprBinding);
    }

    /**
     * Compute LCS-based alignment for different-length sequences.
     */
    private List<TokenAlignment> computeLCSAlignment(List<Token> tokens1, List<Token> tokens2) {
        int[][] lcsTable = computeLCSTable(tokens1, tokens2);
        return extractAlignments(tokens1, tokens2, lcsTable);
    }

    /**
     * Compute LCS table using dynamic programming.
     */
    private int[][] computeLCSTable(List<Token> tokens1, List<Token> tokens2) {
        int m = tokens1.size();
        int n = tokens2.size();
        int[][] table = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (tokens1.get(i - 1).semanticallyMatches(tokens2.get(j - 1))) {
                    table[i][j] = table[i - 1][j - 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
                }
            }
        }

        return table;
    }

    /**
     * Extract token alignments from LCS table.
     */
    private List<TokenAlignment> extractAlignments(List<Token> tokens1, List<Token> tokens2, int[][] table) {
        List<TokenAlignment> alignments = new ArrayList<>();
        int i = tokens1.size();
        int j = tokens2.size();

        while (i > 0 && j > 0) {
            Token t1 = tokens1.get(i - 1);
            Token t2 = tokens2.get(j - 1);

            if (t1.semanticallyMatches(t2)) {
                // Matching tokens
                alignments.add(0, new TokenAlignment(i - 1, j - 1, t1, t2));
                i--;
                j--;
            } else if (table[i - 1][j] >= table[i][j - 1]) {
                // Gap in sequence 2
                alignments.add(0, new TokenAlignment(i - 1, -1, t1, null));
                i--;
            } else {
                // Gap in sequence 1
                alignments.add(0, new TokenAlignment(-1, j - 1, null, t2));
                j--;
            }
        }

        // Add remaining unmatched tokens
        while (i > 0) {
            alignments.add(0, new TokenAlignment(i - 1, -1, tokens1.get(i - 1), null));
            i--;
        }
        while (j > 0) {
            alignments.add(0, new TokenAlignment(-1, j - 1, null, tokens2.get(j - 1)));
            j--;
        }

        return alignments;
    }

    /**
     * Create a variation from two tokens at aligned positions.
     */
    private Variation createVariation(int index1, int index2, Token t1, Token t2) {
        // Categorize variation type
        VariationType varType = categorizeVariation(t1, t2);
        if (varType == null)
            return null;

        return new Variation(
                varType,
                index1,
                index2,
                t1.originalValue(),
                t2.originalValue(),
                t1.inferredType());
    }

    /**
     * Categorize the type of variation between two tokens.
     */
    private VariationType categorizeVariation(Token t1, Token t2) {
        // Same token type but different values
        if (t1.type() == t2.type()) {
            return switch (t1.type()) {
                case STRING_LIT, INT_LIT, LONG_LIT, DOUBLE_LIT, BOOLEAN_LIT, NULL_LIT -> VariationType.LITERAL;
                case VAR -> VariationType.VARIABLE;
                case METHOD_CALL, ASSERT, MOCK -> VariationType.METHOD_CALL;
                case TYPE -> VariationType.TYPE;
                case CONTROL_FLOW -> VariationType.CONTROL_FLOW;
                default -> null;
            };
        }

        // Different token types - potential control flow difference
        if (t1.type() == TokenType.CONTROL_FLOW || t2.type() == TokenType.CONTROL_FLOW) {
            return VariationType.CONTROL_FLOW;
        }

        return null;
    }

    /**
     * Internal record for token alignment.
     */
    private record TokenAlignment(
            int index1,
            int index2,
            Token token1,
            Token token2) {
    }
}
