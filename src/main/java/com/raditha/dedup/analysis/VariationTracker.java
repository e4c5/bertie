package com.raditha.dedup.analysis;

import com.raditha.dedup.model.ExprInfo;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;
import org.slf4j.LoggerFactory;

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

    private static void debugAlignments(List<TokenAlignment> alignments, String x) {
        var log = LoggerFactory.getLogger(VariationTracker.class);
        if (log.isDebugEnabled() && alignments.stream().anyMatch(a -> a.token1() == null || a.token2() == null)) {
            log.debug(x);
            for (TokenAlignment a : alignments) {
                String t1v = a.token1() == null ? "GAP" : a.token1().originalValue();
                String t2v = a.token2() == null ? "GAP" : a.token2().originalValue();
                log.debug("  {} vs {}", t1v, t2v);
            }
        }
    }

    /**
     * Compare two tokens by their AST expressions if available, falling back to
     * text comparison.
     * This ensures that complex expressions like concatenations are compared as a
     * whole.
     */
    private boolean expressionsMatch(Token t1, Token t2) {
        // If both have expressions, compare them structurally
        if (t1.expr() != null && t2.expr() != null) {
            // Use toString() for structural comparison - this compares the entire AST tree
            return t1.expr().toString().equals(t2.expr().toString());
        }

        // If one has expression and other doesn't, they don't match
        if (t1.expr() != null || t2.expr() != null) {
            return false;
        }

        // Both null - fall back to text comparison
        return t1.originalValue().equals(t2.originalValue());
    }

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

        if ((tokens1 == null || tokens2 == null) || (tokens1.isEmpty() && tokens2.isEmpty())) {
            return new VariationAnalysis(
                    List.of(),
                    false,
                    new java.util.HashMap<>(),
                    java.util.Collections.emptyList(),
                    new java.util.HashMap<>(),
                    java.util.Collections.emptyList(), // varyingExpressions
                    java.util.Collections.emptySet()); // variableReferences
        }

        // Find all variations
        List<Variation> variations = new ArrayList<>();
        Map<Integer, Map<StatementSequence, String>> valueBindings = new HashMap<>();
        Map<Integer, Map<StatementSequence, com.raditha.dedup.model.ExprInfo>> exprBindings = new HashMap<>();
        boolean hasControlFlowDifferences = false;

        // For simplicity in Phase 1: use positional alignment if same length
        // For different lengths: use LCS alignment
        if (tokens1.size() == tokens2.size()) {
            hasControlFlowDifferences = isPositionalAligned(tokens1, seq1, tokens2, seq2, variations, valueBindings,
                    exprBindings);
        } else {
            hasControlFlowDifferences = isHasControlFlowDifferences(tokens1, seq1, tokens2, seq2, variations,
                    valueBindings, exprBindings);
        }

        return new VariationAnalysis(variations, hasControlFlowDifferences, valueBindings, tokens1, exprBindings,
                java.util.Collections.emptyList(), // varyingExpressions - will be populated by ASTVariationAnalyzer
                java.util.Collections.emptySet()); // variableReferences - will be populated by ASTVariationAnalyzer
    }

    private boolean isHasControlFlowDifferences(List<Token> tokens1, StatementSequence seq1, List<Token> tokens2,
            StatementSequence seq2, List<Variation> variations,
            Map<Integer, Map<StatementSequence, String>> valueBindings,
            Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings) {
        // Use LCS alignment for different-length sequences
        boolean hasControlFlowDifferences = false;
        List<TokenAlignment> alignments = computeLCSAlignment(tokens1, tokens2);

        // DEBUG ALIGNMENTS
        debugAlignments(alignments, "DEBUG LCS Alignments (Before Coalesce):");

        alignments = coalesceAlignments(alignments);

        debugAlignments(alignments, "DEBUG LCS Alignments (After Coalesce):");

        for (TokenAlignment alignment : alignments) {
            Token token1 = alignment.token1();
            Token token2 = alignment.token2();
            if (token1 != null && token2 != null) {
                // Both tokens exist - check if they differ
                boolean semanticallyMatches = token1.semanticallyMatches(token2);
                boolean isValueMismatch = semanticallyMatches
                        && (token1.type() == TokenType.VAR || isLiteral(token1.type())
                                || token1.type() == TokenType.METHOD_CALL
                                || token1.type() == TokenType.TYPE)
                        && !expressionsMatch(token1, token2);

                if (!semanticallyMatches || isValueMismatch) {
                    Variation variation = createVariation(
                            alignment.index1(),
                            alignment.index2(),
                            token1,
                            token2);
                    if (variation != null) {
                        variations.add(variation);
                        // Key value bindings by aligned source position to avoid index drift
                        captureValueBindings(valueBindings, exprBindings, variation.alignedIndex1(),
                                seq1, token1, seq2,
                                token2);

                        if (variation.type() == VariationType.CONTROL_FLOW) {
                            hasControlFlowDifferences = true;
                        }
                    }
                }
            } else {
                // Gap in one sequence - potential control flow difference
                if ((token1 != null && token1.type() == TokenType.CONTROL_FLOW)
                        || token2 != null && token2.type() == TokenType.CONTROL_FLOW) {
                    hasControlFlowDifferences = true;
                }
            }
        }
        return hasControlFlowDifferences;
    }

    private boolean isPositionalAligned(List<Token> tokens1, StatementSequence seq1, List<Token> tokens2,
            StatementSequence seq2, List<Variation> variations,
            Map<Integer, Map<StatementSequence, String>> valueBindings,
            Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings) {
        // Positional alignment - compare token by token
        boolean hasControlFlowDifferences = false;
        for (int i = 0; i < tokens1.size(); i++) {
            Token t1 = tokens1.get(i);
            Token t2 = tokens2.get(i);

            boolean semanticallyMatches = t1.semanticallyMatches(t2);
            boolean isValueMismatch = semanticallyMatches && (t1.type() == TokenType.VAR || isLiteral(t1.type())
                    || t1.type() == TokenType.METHOD_CALL || t1.type() == TokenType.TYPE)
                    && !expressionsMatch(t1, t2);

            if (!semanticallyMatches || isValueMismatch) {
                Variation variation = createVariation(i, i, t1, t2);
                if (variation != null) {
                    variations.add(variation);
                    // Key value bindings by aligned source position to avoid index drift
                    captureValueBindings(valueBindings, exprBindings, variation.alignedIndex1(), seq1,
                            t1, seq2, t2);

                    if (variation.type() == VariationType.CONTROL_FLOW) {
                        hasControlFlowDifferences = true;
                    }
                }
            }
        }
        return hasControlFlowDifferences;
    }

    /**
     * Coalesce adjacent insertion/deletion pairs into substitutions where possible.
     * This fixes issue where LCS treats replaced literals (Bob vs Charlie) as
     * separate
     * gaps instead of a substitution.
     */
    private List<TokenAlignment> coalesceAlignments(List<TokenAlignment> raw) {
        List<TokenAlignment> coalesced = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            TokenAlignment curr = raw.get(i);

            // Look ahead for substitution pattern
            if (i + 1 < raw.size()) {
                TokenAlignment next = raw.get(i + 1);

                // Case 1: (A, null) then (null, B) -> Merge to (A, B)
                if (curr.token1() != null && curr.token2() == null &&
                        next.token1() == null && next.token2() != null) {

                    // Only merge if types are compatible (e.g. both literals or both vars)
                    if (curr.token1().type() == next.token2().type()) {
                        coalesced.add(new TokenAlignment(curr.index1(), next.index2(), curr.token1(), next.token2()));
                        i++;
                        continue;
                    }
                }

                // Case 2: (null, B) then (A, null) -> Merge to (A, B)
                if (curr.token1() == null && curr.token2() != null &&
                        next.token1() != null && next.token2() == null) {

                    if (next.token1().type() == curr.token2().type()) {
                        coalesced.add(new TokenAlignment(next.index1(), curr.index2(), next.token1(), curr.token2()));
                        i++;
                        continue;
                    }
                }
            }

            coalesced.add(curr);
        }
        return coalesced;
    }

    private void captureValueBindings(
            Map<Integer, Map<StatementSequence, String>> valueBindings,
            Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings,
            int paramIndex,
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

        // New AST-first bindings (ExprInfo) — use actual Expression nodes from tokens
        Map<StatementSequence, com.raditha.dedup.model.ExprInfo> exprBinding = new HashMap<>();
        exprBinding.put(seq1, t1.expr() != null
                ? com.raditha.dedup.model.ExprInfo.fromExpression(t1.expr())
                : com.raditha.dedup.model.ExprInfo.fromText(t1.originalValue()));
        exprBinding.put(seq2, t2.expr() != null
                ? com.raditha.dedup.model.ExprInfo.fromExpression(t2.expr())
                : com.raditha.dedup.model.ExprInfo.fromText(t2.originalValue()));
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

                case METHOD_CALL, ASSERT, MOCK -> {
                    // Check if original values differ (e.g. processJohn vs processAdmin)
                    if (!t1.originalValue().equals(t2.originalValue())) {
                        yield VariationType.METHOD_CALL;
                    }
                    yield null; // No variation
                }

                case TYPE -> {
                    // Check if original type names differ (e.g. User vs Customer)
                    if (!t1.originalValue().equals(t2.originalValue())) {
                        yield VariationType.TYPE;
                    }
                    yield null; // No variation
                }

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

    private boolean isLiteral(TokenType type) {
        return type == TokenType.STRING_LIT ||
                type == TokenType.INT_LIT ||
                type == TokenType.LONG_LIT ||
                type == TokenType.DOUBLE_LIT ||
                type == TokenType.BOOLEAN_LIT ||
                type == TokenType.NULL_LIT;
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
