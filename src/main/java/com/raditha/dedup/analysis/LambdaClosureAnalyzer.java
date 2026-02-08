package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.StatementSequence;

import java.util.*;

/**
 * Analyzes lambda expressions to find variables captured from outer scope
 * (closures).
 */
public class LambdaClosureAnalyzer {

    /**
     * Find all variables captured in the given statement sequence.
     * For LAMBDA containers: finds variables used in the sequence but defined outside.
     * For other containers: finds variables captured by nested lambdas.
     */
    public static Set<String> findAllCapturedVariables(StatementSequence sequence) {
        Set<String> allCaptured = new HashSet<>();

        // Special handling when the container IS a lambda
        if (sequence.containerType() == ContainerType.LAMBDA && sequence.container() instanceof LambdaExpr lambda) {
            allCaptured.addAll(findCapturedVariablesForLambdaContainer(lambda, sequence));
            return allCaptured;
        }

        // For other container types, look for nested lambdas
        for (Statement stmt : sequence.statements()) {
            List<LambdaExpr> lambdas = stmt.findAll(LambdaExpr.class);
            for (LambdaExpr lambda : lambdas) {
                allCaptured.addAll(findCapturedVariables(lambda));
            }
        }

        return allCaptured;
    }

    /**
     * Find captured variables when the sequence container is itself a lambda.
     * Variables are "captured" if used in the lambda but not defined within it.
     */
    private static Set<String> findCapturedVariablesForLambdaContainer(LambdaExpr lambda, StatementSequence sequence) {
        Set<String> captured = new HashSet<>();

        // Lambda parameters are not captured
        Set<String> lambdaParams = new HashSet<>();
        lambda.getParameters().forEach(param -> lambdaParams.add(param.getNameAsString()));

        // Variables declared in the sequence
        Set<String> declaredInSequence = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(VariableDeclarator.class)
                    .forEach(v -> declaredInSequence.add(v.getNameAsString()));
        }

        // Find all variable references in the sequence
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(NameExpr.class).forEach(nameExpr -> {
                String varName = nameExpr.getNameAsString();
                // Skip lambda params and locally declared variables
                if (!lambdaParams.contains(varName) && !declaredInSequence.contains(varName)) {
                    captured.add(varName);
                }
            });
        }

        return captured;
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

    /**
     * Check if any lambda in the sequence captures the given variable.
     */
    public static boolean isVariableCaptured(StatementSequence sequence, String varName) {
        return findAllCapturedVariables(sequence).contains(varName);
    }
}
