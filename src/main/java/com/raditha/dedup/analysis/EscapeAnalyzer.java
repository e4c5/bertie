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
        Set<String> definedLocally = new HashSet<>();
        Set<String> modifiedVariables = new HashSet<>();
        Set<String> readVariables = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            stmt.walk(node -> {
                if (node instanceof VariableDeclarationExpr vde) {
                    vde.getVariables().forEach(v -> definedLocally.add(v.getNameAsString()));
                } else if (node instanceof AssignExpr ae) {
                    if (ae.getTarget().isNameExpr()) {
                        modifiedVariables.add(ae.getTarget().asNameExpr().getNameAsString());
                    }
                } else if (node instanceof UnaryExpr ue) {
                    if (ue.getExpression().isNameExpr()) {
                            modifiedVariables.add(ue.getExpression().asNameExpr().getNameAsString());
                    }
                } else if (node instanceof NameExpr ne) {
                    readVariables.add(ne.getNameAsString());
                }
            });
        }

        // Captured variables: read or modified but NOT defined locally
        Set<String> captured = new HashSet<>();
        captured.addAll(readVariables);
        captured.addAll(modifiedVariables);
        captured.removeAll(definedLocally);

        // Escaping writes: variables modified that come from outer scope
        modifiedVariables.removeAll(definedLocally);

        // Escaping reads: variables read from outer scope
        readVariables.removeAll(definedLocally);

        return new EscapeAnalysis(
                readVariables,
                modifiedVariables,
                captured,
                definedLocally);
    }



    /**
     * Result of escape analysis.
     *
     * Fields:
     * - escapingReads: Variables read from outer scope (can be parameters)
     * - escapingWrites: Variables modified from outer scope (UNSAFE!)
     * - capturedVariables: All variables captured from outer scope
     * - localVariables: Variables defined within the sequence
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
    }
}
