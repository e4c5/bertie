package com.raditha.dedup.analysis;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.*;

/**
 * Analyzes variable escape and capture in code sequences.
 * 
 * This is critical for Gap 8: detects when extracted code modifies
 * variables from outer scope, which would break when extracted.
 * 
 * Example of variable capture:
 * 
 * <pre>
 * int counter = 0;
 * // sequence starts
 * counter++; // ← Modifies outer variable!
 * process(counter);
 * // sequence ends
 * use(counter); // ← Expects modified value
 * </pre>
 * 
 * Extracting this would break because the extracted method
 * can't modify the caller's 'counter' variable.
 */
public class EscapeAnalyzer {

    /**
     * Analyze escape and capture for a sequence.
     * 
     * @return EscapeAnalysis containing all escape/capture information
     */
    public EscapeAnalysis analyze(StatementSequence sequence) {
        Set<String> definedLocally = findLocalVariables(sequence);
        Set<String> modifiedVariables = findModifiedVariables(sequence);
        Set<String> readVariables = findReadVariables(sequence);

        // Captured variables: read or modified but NOT defined locally
        Set<String> captured = new HashSet<>();
        captured.addAll(readVariables);
        captured.addAll(modifiedVariables);
        captured.removeAll(definedLocally);

        // Escaping writes: variables modified that come from outer scope
        Set<String> escapingWrites = new HashSet<>(modifiedVariables);
        escapingWrites.removeAll(definedLocally);

        // Escaping reads: variables read from outer scope
        Set<String> escapingReads = new HashSet<>(readVariables);
        escapingReads.removeAll(definedLocally);

        return new EscapeAnalysis(
                escapingReads,
                escapingWrites,
                captured,
                definedLocally);
    }

    /**
     * Find all variables declared locally within the sequence.
     */
    private Set<String> findLocalVariables(StatementSequence sequence) {
        Set<String> local = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            stmt.findAll(VariableDeclarationExpr.class).forEach(varDecl -> {
                varDecl.getVariables().forEach(v -> {
                    local.add(v.getNameAsString());
                });
            });
        }

        return local;
    }

    /**
     * Find all variables that are MODIFIED (assigned to) in the sequence.
     * 
     * Includes:
     * - Direct assignments: x = 5
     * - Compound assignments: x += 1
     * - Increment/decrement: x++, --x
     */
    private Set<String> findModifiedVariables(StatementSequence sequence) {
        Set<String> modified = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            // Find all assignments
            stmt.findAll(AssignExpr.class).forEach(assign -> {
                if (assign.getTarget().isNameExpr()) {
                    modified.add(assign.getTarget().asNameExpr().getNameAsString());
                }
            });

            // Find all increment/decrement operations
            stmt.findAll(UnaryExpr.class).forEach(unary -> {
                if (unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT ||
                        unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT ||
                        unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT ||
                        unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT) {

                    if (unary.getExpression().isNameExpr()) {
                        modified.add(unary.getExpression().asNameExpr().getNameAsString());
                    }
                }
            });
        }

        return modified;
    }

    /**
     * Find all variables that are READ (used) in the sequence.
     */
    private Set<String> findReadVariables(StatementSequence sequence) {
        Set<String> read = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            // Find all name expressions (variable references)
            stmt.findAll(NameExpr.class).forEach(nameExpr -> {
                read.add(nameExpr.getNameAsString());
            });
        }

        return read;
    }

    /**
     * Check if it's safe to extract this sequence.
     * 
     * Unsafe if:
     * - Sequence modifies variables from outer scope (escaping writes)
     * - These modifications are needed by code after the sequence
     */
    public boolean isSafeToExtract(StatementSequence sequence) {
        EscapeAnalysis analysis = analyze(sequence);

        // If there are escaping writes, extraction would break behavior
        // because the extracted method can't modify caller's variables
        return analysis.escapingWrites().isEmpty();
    }

    /**
     * Result of escape analysis.
     * 
     * @param escapingReads     Variables read from outer scope (can be parameters)
     * @param escapingWrites    Variables modified from outer scope (UNSAFE!)
     * @param capturedVariables All variables captured from outer scope
     * @param localVariables    Variables defined within the sequence
     */
    public record EscapeAnalysis(
            Set<String> escapingReads,
            Set<String> escapingWrites,
            Set<String> capturedVariables,
            Set<String> localVariables) {
        /**
         * Check if this sequence captures (modifies) outer variables.
         */
        public boolean hasCapture() {
            return !escapingWrites.isEmpty();
        }

        /**
         * Get a human-readable description of capture issues.
         */
        public String getCaptureDescription() {
            if (!hasCapture()) {
                return "No variable capture detected";
            }

            return "Modifies outer variables: " + escapingWrites;
        }
    }
}
