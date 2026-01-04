package com.raditha.dedup.analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
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
     * ALIGN START: Ensure both sequences start at structurally similar statements.
     * Fixes issues where one duplicate includes an extra leading statement (like
     * setName)
     * while the other doesn't, causing extraction failures.
     */
    private SimilarityPair alignBoundaries(SimilarityPair pair) {
        StatementSequence seq1 = pair.seq1();
        StatementSequence seq2 = pair.seq2();

        // Safety check to avoid infinite loops or excessive trimming
        int maxTrim = 5;
        int trimmed1 = 0;
        int trimmed2 = 0;

        while (trimmed1 < maxTrim && trimmed2 < maxTrim) {
            if (seq1.statements().isEmpty() || seq2.statements().isEmpty())
                break;

            Statement s1 = seq1.statements().get(0);
            Statement s2 = seq2.statements().get(0);

            if (areSimilar(s1, s2)) {
                break; // Aligned!
            }

            // Mismatch. Check lookahead to see which one is "extra".
            int idx1 = findSimilar(seq1.statements(), s2); // Is s2 later in seq1?
            int idx2 = findSimilar(seq2.statements(), s1); // Is s1 later in seq2?

            if (idx1 > 0 && idx1 <= 3 && idx2 == -1) {
                // s2 exists in seq1. s1 is extra. Trim seq1.
                // Create a trimmed sequence starting from idx1
                List<Statement> trimmedStmts = seq1.statements().subList(idx1, seq1.statements().size());
                seq1 = createTrimmedSequence(seq1, trimmedStmts);
                trimmed1 += idx1;
            } else if (idx2 > 0 && idx2 <= 3 && idx1 == -1) {
                // s1 exists in seq2. s2 is extra. Trim seq2.
                List<Statement> trimmedStmts = seq2.statements().subList(idx2, seq2.statements().size());
                seq2 = createTrimmedSequence(seq2, trimmedStmts);
                trimmed2 += idx2;
            } else {
                // Ambiguous or neither match. Stop aligning.
                break;
            }
        }

        if (seq1 != pair.seq1() || seq2 != pair.seq2()) {
            // If changed, verify minimal length
            if (seq1.statements().size() < minStatements || seq2.statements().size() < minStatements) {
                return pair; // Trimmed too much
            }
            // Recalculate similarity
            SimilarityResult newSim = recalculateSimilarity(seq1, seq2);
            if (newSim.overallScore() >= threshold) {
                return new SimilarityPair(seq1, seq2, newSim);
            } else {
                return pair; // Similarity dropped too low
            }
        }

        return pair;
    }

    private boolean areSimilar(Statement s1, Statement s2) {
        List<Token> t1 = normalizer.normalizeStatements(Collections.singletonList(s1));
        List<Token> t2 = normalizer.normalizeStatements(Collections.singletonList(s2));
        if (t1.size() != t2.size())
            return false;
        for (int i = 0; i < t1.size(); i++) {
            Token tok1 = t1.get(i);
            Token tok2 = t2.get(i);
            if (tok1.type() != tok2.type())
                return false;
            // For keywords/operators, values must match
            // VAR (variables) are allowed to differ (parameterization)
            // Literals are allowed to differ (parameterization)
            if (tok1.type() != com.raditha.dedup.model.TokenType.VAR &&
                    !isLiteral(tok1.type()) &&
                    !tok1.normalizedValue().equals(tok2.normalizedValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isLiteral(com.raditha.dedup.model.TokenType type) {
        return type == com.raditha.dedup.model.TokenType.STRING_LIT ||
                type == com.raditha.dedup.model.TokenType.INT_LIT ||
                type == com.raditha.dedup.model.TokenType.LONG_LIT ||
                type == com.raditha.dedup.model.TokenType.DOUBLE_LIT ||
                type == com.raditha.dedup.model.TokenType.BOOLEAN_LIT ||
                type == com.raditha.dedup.model.TokenType.NULL_LIT;
    }

    private int findSimilar(List<Statement> stmts, Statement target) {
        for (int i = 0; i < Math.min(stmts.size(), 5); i++) {
            if (areSimilar(stmts.get(i), target))
                return i;
        }
        return -1;
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
        if (sequence.containingMethod() == null || !sequence.containingMethod().getBody().isPresent()) {
            return sequence;
        }

        BlockStmt block = sequence.containingMethod().getBody().get();
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

        List<Statement> currentStmts = new ArrayList<>(sequence.statements());
        boolean extended = false;

        // Look backwards from firstIdx - 1
        int currentIdx = firstIdx - 1;
        while (currentIdx >= 0) {
            Statement stmt = allStmts.get(currentIdx);

            // Check if this statement defines one of our captured variables
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

            if (relevantDeclaration) {
                // Prepend statement
                currentStmts.add(0, stmt);
                extended = true;
                currentIdx--;
            } else {
                // Found a gap (statement that is NOT a relevant declaration)
                // Stop extending to ensure contiguous block
                break;
            }
        }

        if (extended) {
            return createTrimmedSequence(sequence, currentStmts); // Re-use creation logic
        }

        return sequence;
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
    private StatementSequence createTrimmedSequence(StatementSequence original, List<Statement> trimmed) {
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot create empty sequence");
        }

        // Get range from first to last statement of trimmed list
        Statement first = trimmed.getFirst();
        Statement last = trimmed.getLast();

        Range newRange = createRange(first, last);

        return new StatementSequence(
                new ArrayList<>(trimmed),
                newRange,
                original.startOffset(),
                original.containingMethod(),
                original.compilationUnit(),
                original.sourceFilePath());
    }

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
