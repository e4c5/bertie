package com.raditha.dedup.analysis;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.stmt.*;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.similarity.SimilarityCalculator;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.detection.TokenNormalizer;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.config.SimilarityWeights;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.TypeCompatibility;
import com.raditha.dedup.analysis.VariationTracker;
import com.raditha.dedup.analysis.TypeAnalyzer;

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
    private final TokenNormalizer normalizer;
    private final SimilarityCalculator similarityCalculator;
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
        this.normalizer = new TokenNormalizer();
        this.similarityCalculator = new SimilarityCalculator();
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
        int totalTrimmed = 0;

        for (SimilarityPair pair : pairs) {
            // Try to trim both sequences
            StatementSequence trimmed1 = trimUsageStatements(pair.seq1());
            StatementSequence trimmed2 = trimUsageStatements(pair.seq2());

            // Check if we actually trimmed anything
            boolean wasTrimmed = trimmed1.statements().size() < pair.seq1().statements().size() ||
                    trimmed2.statements().size() < pair.seq2().statements().size();

            // Skip if trimmed below minimum
            if (trimmed1.statements().size() < minStatements ||
                    trimmed2.statements().size() < minStatements) {
                // Add original pair (trimmed too much)
                refined.add(pair);
                continue;
            }

            // If we trimmed, recalculate similarity
            if (wasTrimmed) {
                SimilarityResult newSimilarity = recalculateSimilarity(trimmed1, trimmed2);

                // Keep trimmed version if still above threshold
                if (newSimilarity.overallScore() >= threshold) {
                    refined.add(new SimilarityPair(trimmed1, trimmed2, newSimilarity));
                    totalTrimmed++;
                } else {
                    // Similarity too low after trimming - keep original
                    refined.add(pair);
                }
            } else {
                // No trimming - keep original
                refined.add(pair);
            }
        }

        if (totalTrimmed > 0) {
            System.out.printf("Boundary refinement: %d pairs trimmed%n", totalTrimmed);
        }

        return refined;
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
            List<Statement> trimmed = stmts.subList(0, lastNonUsage + 1);
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
        stmt.findAll(NameExpr.class).forEach(nameExpr -> {
            used.add(nameExpr.getNameAsString());
        });

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
    private StatementSequence createTrimmedSequence(StatementSequence original, List<Statement> trimmed) {
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot create empty sequence");
        }

        // Get range from first to last statement of trimmed list
        Statement first = trimmed.get(0);
        Statement last = trimmed.get(trimmed.size() - 1);

        com.github.javaparser.Range firstRange = first.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));
        com.github.javaparser.Range lastRange = last.getRange()
                .orElseThrow(() -> new IllegalStateException("Statement missing range"));

        Range newRange = new Range(
                firstRange.begin.line,
                lastRange.end.line,
                firstRange.begin.column,
                lastRange.end.column);

        return new StatementSequence(
                new ArrayList<>(trimmed),
                newRange,
                original.startOffset(),
                original.containingMethod(),
                original.compilationUnit(),
                original.sourceFilePath());
    }

    /**
     * Recalculate similarity for trimmed sequences.
     */
    private SimilarityResult recalculateSimilarity(StatementSequence seq1, StatementSequence seq2) {
        // Normalize both sequences
        List<Token> tokens1 = normalizer.normalizeStatements(seq1.statements());
        List<Token> tokens2 = normalizer.normalizeStatements(seq2.statements());

        // Track variations (simplified - full tracking not needed for threshold check)
        VariationTracker tracker = new VariationTracker();
        VariationAnalysis variations = tracker.trackVariations(tokens1, tokens2);

        // Analyze type compatibility
        TypeAnalyzer typeAnalyzer = new TypeAnalyzer();
        TypeCompatibility typeCompat = typeAnalyzer.analyzeTypeCompatibility(variations);

        // Calculate similarity with balanced weights
        return similarityCalculator.calculate(
                tokens1,
                tokens2,
                SimilarityWeights.balanced(),
                variations,
                typeCompat);
    }
}
