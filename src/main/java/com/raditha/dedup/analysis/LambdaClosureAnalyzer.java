package com.raditha.dedup.analysis;

import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.*;

/**
 * Analyzes lambda expressions to find variables captured from outer scope
 * (closures).
 */
public class LambdaClosureAnalyzer {

    /**
     * Find all variables captured by lambdas in the given statement sequence.
     * A variable is "captured" if it's used inside a lambda but declared outside of
     * it.
     */
    public static Set<String> findAllCapturedVariables(StatementSequence sequence) {
        Set<String> allCaptured = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            List<LambdaExpr> lambdas = stmt.findAll(LambdaExpr.class);
            for (LambdaExpr lambda : lambdas) {
                allCaptured.addAll(findCapturedVariables(lambda));
            }
        }

        return allCaptured;
    }

    /**
     * Find variables captured by a specific lambda expression.
     */
    private static Set<String> findCapturedVariables(LambdaExpr lambda) {
        Set<String> captured = new HashSet<>();

        // Get lambda parameters (these are NOT captured, they're internal)
        Set<String> lambdaParams = new HashSet<>();
        lambda.getParameters().forEach(param -> lambdaParams.add(param.getNameAsString()));

        // Find all variable references inside the lambda
        List<NameExpr> nameExprs = lambda.findAll(NameExpr.class);
        for (NameExpr nameExpr : nameExprs) {
            String varName = nameExpr.getNameAsString();

            // Skip if it's a lambda parameter
            if (lambdaParams.contains(varName) || isDeclaredInLambda(lambda, varName)) {
                continue;
            }

            // This variable is captured from outer scope
            captured.add(varName);
        }

        return captured;
    }

    /**
     * Check if a variable is declared inside the lambda body.
     */
    private static boolean isDeclaredInLambda(LambdaExpr lambda, String varName) {
        if (lambda.getBody().isBlockStmt()) {
            return lambda.getBody().asBlockStmt().findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class)
                    .stream()
                    .flatMap(vd -> vd.getVariables().stream())
                    .anyMatch(v -> v.getNameAsString().equals(varName));
        }
        return false;
    }
}
