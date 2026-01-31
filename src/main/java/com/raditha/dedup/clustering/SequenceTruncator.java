package com.raditha.dedup.clustering;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VaryingExpression;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles truncation logic for statement sequences to ensure safe extraction.
 * Determines how many statements can be safely extracted based on:
 * - Minimum sequence length across duplicates
 * - Structural consistency
 * - Internal variable dependencies
 * - Unsafe return expressions
 */
public class SequenceTruncator {

    private static final String OBJECT = "Object";
    private final DataFlowAnalyzer dataFlowAnalyzer = new DataFlowAnalyzer();

    /**
     * Calculate the valid statement count for extraction.
     * 
     * @param cluster The duplicate cluster
     * @param analysis The variation analysis
     * @return TruncationResult with valid count and optional return variable
     */
    public TruncationResult calculateValidStatementCount(DuplicateCluster cluster, VariationAnalysis analysis) {
        int validStatementCount = -1;
        String primaryReturnVariable = null;
        Set<String> declaredVars = analysis.getDeclaredInternalVariables();

        // Step 1: Find minimum sequence length across all duplicates
        int minSequenceLength = cluster.primary().statements().size();
        for (SimilarityPair pair : cluster.duplicates()) {
            int seq2Size = pair.seq2().statements().size();
            if (seq2Size < minSequenceLength) {
                minSequenceLength = seq2Size;
            }
        }

        if (minSequenceLength < cluster.primary().statements().size()) {
            validStatementCount = minSequenceLength;
        }

        // Step 2: Validate structural consistency
        int structuralLimit = validateStructuralConsistency(cluster,
                validStatementCount != -1 ? validStatementCount : minSequenceLength);
        if (structuralLimit < minSequenceLength
                || (validStatementCount != -1 && structuralLimit < validStatementCount)) {
            validStatementCount = structuralLimit;
        }

        // Step 2b: Truncate before any nested return statement (safety rule)
        int nestedReturnLimit = findNestedReturnLimit(cluster);
        if (nestedReturnLimit != -1
                && (validStatementCount == -1 || nestedReturnLimit < validStatementCount)) {
            validStatementCount = nestedReturnLimit;
        }

        // Step 2c: Reduce prefix until we have at most 1 live-out variable across all sequences
        int liveOutLimit = reduceToSingleLiveOut(cluster, validStatementCount == -1 ? minSequenceLength : validStatementCount);
        if (liveOutLimit != -1 && (validStatementCount == -1 || liveOutLimit < validStatementCount)) {
            validStatementCount = liveOutLimit;
        }

        // Step 3: Check varying expressions for internal dependencies or unsafe return types
        for (VaryingExpression vae : analysis.getVaryingExpressions()) {
            int stmtIndex = vae.position() >> 16; // Decode statement index

            // Check for type mismatch in ReturnStatement (unsafe to parameterize)
            if (vae.type() == null || OBJECT.equals(vae.type().describe())) {
                if (isReturnExpression(vae.expr1())) {
                    if (validStatementCount == -1 || stmtIndex < validStatementCount) {
                        validStatementCount = stmtIndex;
                    }
                }
            }

            // Check internal variable dependency
            Set<String> usedInternalVars = new HashSet<>();
            vae.expr1().findAll(com.github.javaparser.ast.expr.NameExpr.class).forEach(n -> {
                if (declaredVars.contains(n.getNameAsString())) {
                    usedInternalVars.add(n.getNameAsString());
                }
            });

            if (!usedInternalVars.isEmpty()) {
                if (validStatementCount == -1 || stmtIndex < validStatementCount) {
                    validStatementCount = stmtIndex;
                }
                if (usedInternalVars.size() == 1) {
                    primaryReturnVariable = usedInternalVars.iterator().next();
                }
            }
        }

        return new TruncationResult(validStatementCount, primaryReturnVariable);
    }

    private int findNestedReturnLimit(DuplicateCluster cluster) {
        int limit = -1;
        for (StatementSequence seq : cluster.allSequences()) {
            int idx = firstNestedReturnIndex(seq);
            if (idx != -1 && (limit == -1 || idx < limit)) {
                limit = idx;
            }
        }
        return limit;
    }

    private int firstNestedReturnIndex(StatementSequence sequence) {
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);
            if (stmt.isReturnStmt() && i == stmts.size() - 1) {
                continue;
            }
            if (stmt.findFirst(com.github.javaparser.ast.stmt.ReturnStmt.class).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private int reduceToSingleLiveOut(DuplicateCluster cluster, int limit) {
        int minSize = cluster.allSequences().stream()
                .mapToInt(seq -> seq.statements().size())
                .min()
                .orElse(0);
        int current = Math.min(limit, minSize);
        while (current > 0) {
            boolean allSafe = true;
            for (StatementSequence seq : cluster.allSequences()) {
                StatementSequence prefix = createPrefixSequence(seq, current);
                if (dataFlowAnalyzer.findLiveOutVariables(prefix).size() > 1) {
                    allSafe = false;
                    break;
                }
            }
            if (allSafe) {
                return current;
            }
            current--;
        }
        return -1;
    }

    /**
     * Create a prefix sequence containing only the first 'count' statements.
     */
    public StatementSequence createPrefixSequence(StatementSequence fullSequence, int count) {
        if (count >= fullSequence.statements().size()) {
            return fullSequence;
        }
        Range fullRange = fullSequence.range();
        if (count <= 0) {
            // Return empty sequence or smallest possible valid chunk? 
            // In most cases count=0 means invalid truncation, so return empty list
            return new StatementSequence(
                    java.util.Collections.emptyList(),
                    new Range(fullRange.startLine(), fullRange.startColumn(), fullRange.startLine(), fullRange.startColumn()),
                    fullSequence.startOffset(),
                    fullSequence.containingMethod(),
                    fullSequence.compilationUnit(),
                    fullSequence.sourceFilePath());
        }
        List<Statement> prefixStmts = fullSequence.statements().subList(0, count);

        // Calculate new range based on prefix statements
        int endLine = prefixStmts.get(count - 1).getEnd().map(p -> p.line).orElse(fullRange.endLine());
        int endColumn = prefixStmts.get(count - 1).getEnd().map(p -> p.column).orElse(fullRange.endColumn());

        Range prefixRange = new Range(fullRange.startLine(), fullRange.startColumn(), endLine, endColumn);

        return new StatementSequence(
                prefixStmts,
                prefixRange,
                fullSequence.startOffset(),
                fullSequence.containingMethod(),
                fullSequence.compilationUnit(),
                fullSequence.sourceFilePath());
    }

    /**
     * Validate that all duplicates are structurally consistent with the primary
     * sequence up to the limit.
     * 
     * @return The number of statements that are safe to extract (0 to limit)
     */
    private int validateStructuralConsistency(DuplicateCluster cluster, int limit) {
        StatementSequence primary = cluster.primary();
        int safeLimit = limit;

        for (SimilarityPair pair : cluster.duplicates()) {
            StatementSequence duplicate = pair.seq2();
            int currentSafe = 0;

            int loopMax = Math.min(limit, Math.min(primary.statements().size(), duplicate.statements().size()));

            for (int i = 0; i < loopMax; i++) {
                Statement s1 = primary.statements().get(i);
                Statement s2 = duplicate.statements().get(i);

                if (!areStructurallyCompatible(s1, s2)) {
                    break;
                }
                currentSafe++;
            }

            if (currentSafe < safeLimit) {
                safeLimit = currentSafe;
            }
        }
        return safeLimit;
    }

    private boolean areStructurallyCompatible(com.github.javaparser.ast.Node n1, com.github.javaparser.ast.Node n2) {
        if (n1.getMetaModel() != n2.getMetaModel()) {
            return false;
        }

        // METHOD CALLS: Must have same method name
        if (n1 instanceof MethodCallExpr mc1) {
            MethodCallExpr mc2 = (MethodCallExpr) n2;
            if (!mc1.getNameAsString().equals(mc2.getNameAsString())) {
                return false;
            }
        }

        // Recurse on children
        List<com.github.javaparser.ast.Node> children1 = n1.getChildNodes();
        List<com.github.javaparser.ast.Node> children2 = n2.getChildNodes();

        if (children1.size() != children2.size()) {
            return false;
        }

        for (int i = 0; i < children1.size(); i++) {
            if (!areStructurallyCompatible(children1.get(i), children2.get(i))) {
                return false;
            }
        }

        return true;
    }

    private boolean isReturnExpression(Expression expr) {
        return expr.findAncestor(com.github.javaparser.ast.stmt.ReturnStmt.class).isPresent();
    }
}
