package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.*;

/**
 * Performs data flow analysis to determine which variables are live
 * after a sequence of statements.
 * 
 * This is critical for Gap 5: ensures we return the CORRECT variable,
 * not just the first one of matching type.
 */
public class DataFlowAnalyzer {

    /**
     * Find variables that are:
     * 1. Defined within the sequence (assignment or declaration)
     * 2. Used AFTER the sequence ends (live out)
     * 
     * This identifies which variables must be returned from extracted method.
     */
    public Set<String> findLiveOutVariables(StatementSequence sequence) {
        Set<String> definedVars = findDefinedVariables(sequence);
        Set<String> usedAfter = findVariablesUsedAfter(sequence);

        // Return intersection: defined in sequence AND used after
        Set<String> liveOut = new HashSet<>(definedVars);
        liveOut.retainAll(usedAfter);

        return liveOut;
    }

    /**
     * Find all variables defined (assigned or declared) in the sequence.
     */
    public Set<String> findDefinedVariables(StatementSequence sequence) {
        Set<String> defined = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            // Variable declarations: User user = ...
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    expr.asVariableDeclarationExpr().getVariables()
                            .forEach(v -> defined.add(v.getNameAsString()));
                }
                // Assignments: user = ...
                if (expr.isAssignExpr()) {
                    var target = expr.asAssignExpr().getTarget();
                    if (target.isNameExpr()) {
                        defined.add(target.asNameExpr().getNameAsString());
                    }
                }
            }
        }

        return defined;
    }

    /**
     * Find variables used AFTER the sequence ends in the containing method.
     * 
     * This identifies which variables are "live" - needed by subsequent code.
     */
    public Set<String> findVariablesUsedAfter(StatementSequence sequence) {
        Set<String> usedAfter = new HashSet<>();

        MethodDeclaration method = sequence.containingMethod();
        if (method == null || !method.getBody().isPresent()) {
            return usedAfter;
        }

        BlockStmt methodBody = method.getBody().get();
        List<Statement> allStatements = methodBody.getStatements();

        // Find where sequence ends
        int sequenceEnd = sequence.startOffset() + sequence.statements().size();

        // Analyze all statements AFTER the sequence
        for (int i = sequenceEnd; i < allStatements.size(); i++) {
            Statement stmt = allStatements.get(i);

            // Find all variable references in this statement
            stmt.findAll(NameExpr.class).forEach(nameExpr -> {
                usedAfter.add(nameExpr.getNameAsString());
            });
        }

        return usedAfter;
    }

    /**
     * Find the correct return variable for an extracted method.
     * 
     * Returns the variable that is:
     * 1. Defined in the sequence
     * 2. Used after the sequence
     * 3. Matches the expected return type
     * 
     * Returns null if no suitable variable found or multiple candidates.
     */
    public String findReturnVariable(StatementSequence sequence, String returnType) {
        if ("void".equals(returnType)) {
            return null;
        }

        Set<String> liveOut = findLiveOutVariables(sequence);

        // Filter by type
        List<String> candidates = new ArrayList<>();
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
                    for (var variable : varDecl.getVariables()) {
                        String varName = variable.getNameAsString();
                        String varType = variable.getType().asString();

                        // Must be live out AND match type
                        if (liveOut.contains(varName) &&
                                (varType.contains(returnType) || returnType.contains(varType))) {
                            candidates.add(varName);
                        }
                    }
                }
            }
        }

        // Only return if there's exactly ONE candidate
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // Multiple candidates or no candidates = unsafe to extract
        return null;
    }

    /**
     * Check if it's safe to extract the sequence.
     * 
     * Unsafe if:
     * - Multiple variables are live out (can't return multiple values)
     * - Live-out variable of wrong type
     */
    public boolean isSafeToExtract(StatementSequence sequence, String expectedReturnType) {
        String returnVar = findReturnVariable(sequence, expectedReturnType);

        // If "void" is expected, just check no important variables escape
        if ("void".equals(expectedReturnType)) {
            return findLiveOutVariables(sequence).isEmpty();
        }

        // Otherwise, must have exactly one return variable
        return returnVar != null;
    }
}
