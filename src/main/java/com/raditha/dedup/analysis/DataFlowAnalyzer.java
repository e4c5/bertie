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
    /**
     * Find variables used AFTER the sequence ends in the containing method.
     * 
     * Uses physical source code ordering (Ranges) to be robust against
     * list index shifting caused by boundary refinement.
     */
    public Set<String> findVariablesUsedAfter(StatementSequence sequence) {
        Set<String> usedAfter = new HashSet<>();

        MethodDeclaration method = sequence.containingMethod();
        if (method == null || method.getBody().isEmpty()) {
            return usedAfter;
        }

        // Get the specific end line/column of the sequence
        // We use Range to be absolutely sure about ordering
        int endLine = sequence.range().endLine();
        int endColumn = sequence.range().endColumn();

        // Scan the entire method body for variable usages
        BlockStmt methodBody = method.getBody().get();
        methodBody.findAll(NameExpr.class).forEach(nameExpr -> {
            // Check if this usage is physically AFTER the sequence
            if (nameExpr.getRange().isPresent()) {
                var range = nameExpr.getRange().get();
                boolean isAfter = false;

                if (range.begin.line > endLine) {
                    isAfter = true;
                } else if (range.begin.line == endLine && range.begin.column > endColumn) {
                    isAfter = true;
                }

                if (isAfter) {
                    usedAfter.add(nameExpr.getNameAsString());
                }
            }
        });

        return usedAfter;
    }

    /**
     * Find all variables used (referenced) within the sequence.
     */
    public Set<String> findVariablesUsedInSequence(StatementSequence sequence) {
        Set<String> used = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(NameExpr.class).forEach(nameExpr -> {
                used.add(nameExpr.getNameAsString());
            });
        }
        return used;
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
        System.out.println("Analyzing return variable for type: " + returnType);
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
                    for (var variable : varDecl.getVariables()) {
                        String varName = variable.getNameAsString();
                        String varType = variable.getType().asString();

                        // Must be live out OR returned in a return statement within the sequence
                        boolean isLiveOut = liveOut.contains(varName);
                        boolean isReturned = false;

                        // Check if this variable is used in any return statement in the sequence
                        for (Statement s : sequence.statements()) {
                            if (s.isReturnStmt() && s.asReturnStmt().getExpression().isPresent()) {
                                List<NameExpr> nameExprs = s.asReturnStmt().getExpression().get()
                                        .findAll(NameExpr.class);
                                for (NameExpr nameExpr : nameExprs) {
                                    if (nameExpr.getNameAsString().equals(varName)) {
                                        isReturned = true;
                                        break;
                                    }
                                }
                            }
                        }

                        // DEBUG

                        System.out.println("  Var: " + varName + " (" + varType + ")");
                        System.out.println("    Default defined liveOut: " + isLiveOut);
                        System.out.println("    Defined in return: " + isReturned);

                        if ((isLiveOut || isReturned) &&
                                (varType.contains(returnType) || returnType.contains(varType))) {
                            candidates.add(varName);
                        }
                    }
                }
            }
        }

        // Only return if there's exactly ONE candidate
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        // FALLBACK: If standard analysis found nothing, check if there is exactly
        // one DEFINED variable matching the return type.
        // This handles cases where:
        // 1. Live-out analysis missed a usage (false negative)
        // 2. The variable is defined but not used (safe to return/assign)
        if (candidates.isEmpty() && !"void".equals(returnType)) {
            List<String> fallbackCandidates = new ArrayList<>();
            for (Statement stmt : sequence.statements()) {
                if (stmt.isExpressionStmt()) {
                    var expr = stmt.asExpressionStmt().getExpression();
                    if (expr.isVariableDeclarationExpr()) {
                        expr.asVariableDeclarationExpr().getVariables().forEach(v -> {
                            if (v.getType().asString().equals(returnType)) { // Exact match prefered? Or contains?
                                fallbackCandidates.add(v.getNameAsString());
                            } else if (v.getType().asString().contains(returnType)
                                    || returnType.contains(v.getType().asString())) {
                                fallbackCandidates.add(v.getNameAsString());
                            }
                        });
                    }
                }
            }

            if (fallbackCandidates.size() == 1) {
                return fallbackCandidates.getFirst();
            }
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
