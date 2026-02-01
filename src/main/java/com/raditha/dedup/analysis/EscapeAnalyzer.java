package com.raditha.dedup.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
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
     * @return Set of variable names that are modified but not defined locally (escaping variables)
     */
    public Set<String> analyze(StatementSequence sequence) {
        Set<String> definedLocally = new HashSet<>();
        Set<String> modifiedVariables = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            stmt.walk(node ->
                analyzeVariables(node, definedLocally, modifiedVariables)
            );
        }

        // Escaping writes: variables modified that come from outer scope
        modifiedVariables.removeAll(definedLocally);
        return modifiedVariables;
    }

    private static void analyzeVariables(Node node, Set<String> definedLocally, Set<String> modifiedVariables) {
        if (node instanceof VariableDeclarationExpr vde) {
            vde.getVariables().forEach(v -> definedLocally.add(v.getNameAsString()));
        } else if (node instanceof AssignExpr ae) {
            analyzeAssignment(ae.getTarget(), modifiedVariables);
        } else if (node instanceof UnaryExpr ue) {
            analyzeAssignment(ue.getExpression(), modifiedVariables);
        }
    }

    private static void analyzeAssignment(Expression ae, Set<String> modifiedVariables) {
        if (ae.isNameExpr()) {
            modifiedVariables.add(ae.asNameExpr().getNameAsString());
        }
    }
}
