package com.raditha.dedup.analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.similarity.ASTSimilarityCalculator;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import com.raditha.dedup.config.SimilarityWeights;

import java.util.*;

/**
 * Refines duplicate boundaries by trimming usage-only trailing statements.
 * 
 * This component detects when the last statement(s) in a duplicate sequence
 * only READ variables created earlier in the sequence (without modifying them),
 * and trims those statements to enable correct return value detection.
 * 
 * Example:
 * 
 * <pre>
 * // Before:
 * User user = new User(); // defines 'user'
 * user.setName("John"); // modifies 'user'
 * System.out.println(user); // only reads 'user' ← TRIMMED
 * 
 * // After trimming:
 * User user = new User();
 * user.setName("John");
 * // → 'user' is now live-out, return type = User
 * </pre>
 */
public class BoundaryRefiner {

    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final ASTNormalizer normalizer;
    private final ASTSimilarityCalculator similarityCalculator;
    private final int minStatements;
    private final double threshold;

    /**
     * Create boundary refiner with dependencies.
     * 
     * @param dataFlowAnalyzer Analyzer for variable usage
     * @param minStatements    Minimum sequence length after trimming
     * @param threshold        Minimum similarity for trimmed sequences
     */
    public BoundaryRefiner(DataFlowAnalyzer dataFlowAnalyzer, int minStatements, double threshold) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
        this.normalizer = new ASTNormalizer();
        this.similarityCalculator = new ASTSimilarityCalculator();
        this.minStatements = minStatements;
        this.threshold = threshold;
    }

    /**
     * Refine boundaries of duplicate pairs by trimming usage-only statements.
     * 
     * Conservative approach:
     * - Only trims obvious read-only access (variable.method(), variable in
     * expressions)
     * - Does NOT trim control flow (if/while/for/return)
     * - Does NOT trim statements that define new variables
     * - Does NOT trim statements that modify variables (assignments, ++, etc.)
     * 
     * @param pairs List of similarity pairs to refine
     * @return Refined pairs with trimmed sequences
     */
    public List<SimilarityPair> refineBoundaries(List<SimilarityPair> pairs) {
        List<SimilarityPair> refined = new ArrayList<>();

        for (SimilarityPair pair : pairs) {
            // 1. TRIM END: Remove usage-only statements
            StatementSequence processed1 = trimUsageStatements(pair.seq1());
            StatementSequence processed2 = trimUsageStatements(pair.seq2());

            boolean wasTrimmed = processed1.statements().size() < pair.seq1().statements().size() ||
                    processed2.statements().size() < pair.seq2().statements().size();

            // 2. EXTEND START: Include missing variable declarations
            // This fixes "cannot find symbol" errors when duplicates use but don't include
            // the definition
            StatementSequence extended1 = extendStartBoundary(processed1);
            StatementSequence extended2 = extendStartBoundary(processed2);

            boolean wasExtended = extended1.statements().size() > processed1.statements().size() ||
                    extended2.statements().size() > processed2.statements().size();

            // Skip if trimmed below minimum (check ONLY if we trimmed, extension is always
            // safe)
            if (wasTrimmed && (extended1.statements().size() < minStatements ||
                    extended2.statements().size() < minStatements)) {
                // Even with extension, it's too small after trimming? Original might be better.
                refined.add(pair);
                continue;
            }

            // If changed, recalculate similarity
            if (wasTrimmed || wasExtended) {
                SimilarityResult newSimilarity = recalculateSimilarity(extended1, extended2);

                // Keep processed version if still above threshold
                if (newSimilarity.overallScore() >= threshold) {
                    refined.add(new SimilarityPair(extended1, extended2, newSimilarity));
                } else {
                    // Similarity too low - keep original
                    refined.add(pair);
                }
            } else {
                // No change - keep original
                refined.add(pair);
            }
        }

        return refined;
    }


    /**
     * Extend start boundary to include variable declarations.
     * If a variable is used in the sequence but not defined, and its declaration
     * immediately precedes the sequence, include it.
     */
    private StatementSequence extendStartBoundary(StatementSequence sequence) {
        // Find variables used but not defined (captured)
        Set<String> defined = dataFlowAnalyzer.findDefinedVariables(sequence);
        Set<String> used = dataFlowAnalyzer.findVariablesUsedInSequence(sequence);
        Set<String> captured = new HashSet<>(used);
        captured.removeAll(defined);

        if (captured.isEmpty()) {
            return sequence;
        }

        // Get the parent block to access preceding statements
        if (sequence.containingCallable() == null || sequence.getCallableBody().isEmpty()) {
            return sequence;
        }

        BlockStmt block = sequence.getCallableBody().get();
        NodeList<Statement> allStmts = block.getStatements();

        // Find index of first statements
        int firstIdx = -1;
        int firstLine = sequence.statements().get(0).getRange().map(r -> r.begin.line).orElse(-1);

        for (int i = 0; i < allStmts.size(); i++) {
            if (allStmts.get(i).getRange().map(r -> r.begin.line).orElse(-1) == firstLine) {
                firstIdx = i;
                break;
            }
        }

        if (firstIdx <= 0) {
            return sequence; // Cannot extend backwards
        }

        Deque<Statement> merged = new ArrayDeque<>();
        boolean extended = false;

        // Look backwards from firstIdx - 1
        int currentIdx = firstIdx - 1;
        while (currentIdx >= 0) {
            Statement stmt = allStmts.get(currentIdx);

            // Check if this statement defines one of our captured variables
            boolean relevantDeclaration = isRelevantDeclaration(stmt, captured);

            if (relevantDeclaration) {
                // Prepend statement efficiently
                merged.addFirst(stmt);
                extended = true;
                currentIdx--;
            } else {
                // Found a gap (statement that is NOT a relevant declaration)
                // Stop extending to ensure contiguous block
                break;
            }
        }

        if (extended) {
            // Append the original sequence statements preserving order
            for (Statement s : sequence.statements()) {
                merged.addLast(s);
            }
            return createTrimmedSequence(sequence, merged); // Re-use creation logic
        }

        return sequence;
    }

    private static boolean isRelevantDeclaration(Statement stmt, Set<String> captured) {
        boolean relevantDeclaration = false;
        if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
            VariableDeclarationExpr vde = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
            for (var variable : vde.getVariables()) {
                if (captured.contains(variable.getNameAsString())) {
                    relevantDeclaration = true;
                    // It is now defined, remove from captured set to stop looking for it?
                    // Ideally yes, but we might want to keep going for others.
                    break;
                }
            }
        }
        return relevantDeclaration;
    }

    /**
     * Trim trailing usage-only statements from a sequence.
     * 
     * Works backwards from the end, removing statements that only read
     * variables defined earlier in the sequence.
     * 
     * @param sequence Sequence to trim
     * @return Trimmed sequence, or original if no trimming needed
     */
    private StatementSequence trimUsageStatements(StatementSequence sequence) {
        List<Statement> stmts = sequence.statements();

        if (stmts.size() <= minStatements) {
            return sequence; // Already at minimum
        }

        // Find all variables defined in the sequence
        Set<String> definedVars = dataFlowAnalyzer.findDefinedVariables(sequence);

        if (definedVars.isEmpty()) {
            return sequence; // No variables to track
        }

        // Work backwards to find last non-usage statement
        int lastNonUsage = stmts.size() - 1;

        while (lastNonUsage >= minStatements) {
            Statement stmt = stmts.get(lastNonUsage);

            // Check if this statement is usage-only
            if (!isUsageOnlyStatement(stmt, definedVars)) {
                break; // Found first non-usage statement from end
            }

            lastNonUsage--;
        }

        // If we can trim anything
        if (lastNonUsage < stmts.size() - 1) {
            Deque<Statement> trimmed = new ArrayDeque<>(stmts.subList(0, lastNonUsage + 1));
            return createTrimmedSequence(sequence, trimmed);
        }

        return sequence; // No trimming needed
    }

    /**
     * Check if a statement only USES variables (no definition, no modification).
     * 
     * Conservative approach - only returns true for obvious read-only cases:
     * - Expression statements that read variables (no assignments)
     * - Method calls on variables
     * - Variable references in expressions
     * 
     * Returns FALSE for:
     * - Control flow (if, while, for, return, throw, etc.)
     * - Variable declarations
     * - Assignments or modifications
     * - Empty statements or blocks
     * 
     * @param stmt        Statement to check
     * @param definedVars Variables defined earlier in sequence
     * @return true if statement only reads from definedVars
     */
    private boolean isUsageOnlyStatement(Statement stmt, Set<String> definedVars) {
        // Control flow statements - do NOT trim (they affect program logic)
        if (stmt.isIfStmt() || stmt.isWhileStmt() || stmt.isForStmt() ||
                stmt.isForEachStmt() || stmt.isDoStmt() || stmt.isReturnStmt() ||
                stmt.isThrowStmt() || stmt.isTryStmt() || stmt.isSwitchStmt()) {
            return false;
        }

        // Only handle expression statements for now (conservative)
        if (!stmt.isExpressionStmt()) {
            return false;
        }

        Expression expr = stmt.asExpressionStmt().getExpression();

        // Variable declarations - not usage only (defines new vars)
        if (expr.isVariableDeclarationExpr()) {
            return false;
        }

        // Assignments - not usage only (modifies vars)
        if (expr.isAssignExpr()) {
            return false;
        }

        // Unary expressions (++, --, etc.) - not usage only (modifies vars)
        if (expr.isUnaryExpr()) {
            UnaryExpr unary = expr.asUnaryExpr();
            if (isModifyingOperator(unary.getOperator())) {
                return false;
            }
        }

        // Find all variables USED in this statement
        Set<String> usedVars = findUsedVariables(stmt);

        // Only allow trimming if:
        // 1. Statement uses at least one defined variable
        // 2. All used variables are from the defined set (no external deps)
        if (usedVars.isEmpty()) {
            return false; // Doesn't use any variables
        }

        // Check if all used vars are from our defined set
        // (This ensures the statement depends on the sequence's output)
        return definedVars.containsAll(usedVars);
    }

    /**
     * Find all variable names used (read) in a statement.
     */
    private Set<String> findUsedVariables(Statement stmt) {
        Set<String> used = new HashSet<>();

        // Find all name expressions (variable references)
        stmt.findAll(NameExpr.class).forEach(nameExpr -> used.add(nameExpr.getNameAsString()));

        return used;
    }

    /**
     * Check if a unary operator modifies its operand.
     */
    private boolean isModifyingOperator(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT ||
                op == UnaryExpr.Operator.PREFIX_DECREMENT ||
                op == UnaryExpr.Operator.POSTFIX_INCREMENT ||
                op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    /**
     * Create a new StatementSequence from a trimmed list of statements.
     */
    private StatementSequence createTrimmedSequence(StatementSequence original, Deque<Statement> trimmed) {
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot create empty sequence");
        }

        // Get range from first to last statement of trimmed deque
        Statement first = trimmed.getFirst();
        Statement last = trimmed.getLast();

        Range newRange = createRange(first, last);

        return new StatementSequence(
                new ArrayList<>(trimmed),
                newRange,
                original.startOffset(),
                original.containingCallable(),
                original.compilationUnit(),
                original.sourceFilePath());
    }

    /**
     * Create a range covering the span from the first to the last statement.
     *
     * @param first The first statement
     * @param last  The last statement
     * @return A Range object covering the span
     */
    public static Range createRange(Statement first, Statement last) {
        com.github.javaparser.Range firstRange = first.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));
        com.github.javaparser.Range lastRange = last.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));

        return new Range(
                firstRange.begin.line,
                lastRange.end.line,
                firstRange.begin.column,
                lastRange.end.column);

    }

    /**
     * Recalculate similarity for trimmed sequences.
     */
    private SimilarityResult recalculateSimilarity(StatementSequence seq1, StatementSequence seq2) {
        // Normalize both sequences using AST normalization
        List<NormalizedNode> n1 = normalizer.normalize(seq1.statements());
        List<NormalizedNode> n2 = normalizer.normalize(seq2.statements());

        // Calculate similarity using AST-based calculator
        // Note: ASTSimilarityCalculator calculates variations internally if needed,
        // or returns a result without detailed variations if they aren't required for
        // the score.
        return similarityCalculator.calculate(n1, n2, SimilarityWeights.balanced());
    }
}
