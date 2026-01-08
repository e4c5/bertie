package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Performs data flow analysis to determine which variables are live
 * after a sequence of statements.
 * <p>
 * This is critical for Gap 5: ensures we return the CORRECT variable,
 * not just the first one of matching type.
 */
public class DataFlowAnalyzer {

    private static void findCandidate(StatementSequence sequence, Set<String> liveOut, VariableDeclarationExpr expr, List<String> candidates) {
        VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
        for (var variable : varDecl.getVariables()) {
            String varName = variable.getNameAsString();

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

            // FIXED: Accept if live-out OR returned, regardless of type
            if (isLiveOut || isReturned) {
                candidates.add(varName);
            }
        }

    }

    private static String findBestCandidate(List<String> candidates, StatementSequence sequence) {
        // Prefer the first candidate whose type is not primitive-like
        for (String varName : candidates) {
            for (Statement stmt : sequence.statements()) {
                if (stmt instanceof ExpressionStmt expr
                        && expr.getExpression() instanceof VariableDeclarationExpr varDecl) {
                    for (var variable : varDecl.getVariables()) {
                        var type = variable.getType();
                        if (variable.getNameAsString().equals(varName) && !isPrimitiveLike(type)) {
                            return varName;
                        }
                    }
                }
            }
        }
        // If all are primitive-like, return first
        return candidates.getFirst();
    }

    private static boolean isPrimitiveLike(com.github.javaparser.ast.type.Type type) {
        if (type.isClassOrInterfaceType()) {
            return type.asClassOrInterfaceType().getNameAsString().equals("String");
        }
        return type.isPrimitiveType();
    }

    /**
     * Find variables that are:
     * 1. Defined within the sequence (assignment or declaration)
     * 2. Used AFTER the sequence ends (live out)
     * <p>
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
            // 1. Variable declarations (including nested ones)
            stmt.findAll(VariableDeclarationExpr.class).forEach(vde ->
                    vde.getVariables().forEach(v ->
                            defined.add(v.getNameAsString())
                    )
            );

            // 2. Assignments (target variables)
            stmt.findAll(com.github.javaparser.ast.expr.AssignExpr.class).forEach(ae -> {
                var target = ae.getTarget();
                if (target.isNameExpr()) {
                    defined.add(target.asNameExpr().getNameAsString());
                }
            });

            // 3. Lambda parameters
            stmt.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).forEach(lambda ->
                    lambda.getParameters().forEach(p -> defined.add(p.getNameAsString()))
            );
        }

        return defined;
    }

    /**
     * Find variables used AFTER the sequence ends in the containing method.
     * <p>
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
                if (range.begin.line > endLine || (range.begin.line == endLine && range.begin.column > endColumn)) {
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
            stmt.findAll(NameExpr.class).forEach(nameExpr -> used.add(nameExpr.getNameAsString()));
        }
        return used;
    }

    public List<String> findCandidates(StatementSequence sequence, Set<String> liveOut) {
        List<String> candidates = new ArrayList<>();

        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    findCandidate(sequence, liveOut, expr.asVariableDeclarationExpr(), candidates);
                }
            }
        }
        return candidates;
    }

    /**
     * Find the correct return variable for an extracted method.
     * <p>
     * Returns the variable that is:
     * 1. Defined in the sequence
     * 2. Used after the sequence
     * 3. Matches the expected return type
     * <p>
     * Returns null if no suitable variable found or multiple candidates.
     */
    public String findReturnVariable(StatementSequence sequence, String returnType) {
        Set<String> liveOut = findLiveOutVariables(sequence);

        List<String> candidates = findCandidates(sequence, liveOut);

        // Filter candidates by type compatibility
        if (returnType != null && !"void".equals(returnType)) {
            candidates.removeIf(candidate -> !isTypeCompatible(sequence, candidate, returnType));
        }

        // Only return if there's exactly ONE candidate
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        // If multiple candidates remain, pick the best one (non-primitive preferred)
        if (candidates.size() > 1) {
            return findBestCandidate(candidates, sequence);
        }

        // FALLBACK: If standard analysis found nothing, check if there is exactly
        // one DEFINED variable matching the return type.
        // This handles cases where:
        // 1. Live-out analysis missed a usage (false negative)
        return findFallBackCandidate(sequence, returnType);
    }

    private String findFallBackCandidate(StatementSequence sequence, String returnType) {
        if ("void".equals(returnType)) {
            return null;
        }

        List<String> fallbackCandidates = new ArrayList<>();
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    expr.asVariableDeclarationExpr().getVariables().forEach(v -> {
                        if (v.getType().asString().equals(returnType) || v.getType().asString().contains(returnType)
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

        return null;
    }

    /**
     * Check if it's safe to extract the sequence.
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

    /**
     * Check if a variable's type is compatible with the expected return type.
     * Uses AbstractCompiler and TypeWrapper for robust type checking.
     */
    private boolean isTypeCompatible(StatementSequence sequence, String varName, String expectedType) {
        // 1. Find the variable declaration to get its types

        com.github.javaparser.ast.body.VariableDeclarator targetVar = null;

        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                var decl = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                for (var v : decl.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        targetVar = v;
                        break;
                    }
                }
            }
            if (targetVar != null)
                break;
        }

        if (targetVar == null)
            return false;

        // 2. Resolve types for the variable
        // findTypesInVariable returns list of wrappers. For generics like List<User>,
        // it returns [User, List].
        // We are interested in the main type (last one).
        List<TypeWrapper> varTypes = AbstractCompiler.findTypesInVariable(targetVar);
        if (varTypes.isEmpty())
            return false;
        TypeWrapper varWrapper = varTypes.getLast();

        // 3. Resolve the expected type
        TypeWrapper expectedWrapper;

        String simpleType = expectedType;
        if (simpleType.contains("<")) {
            // Strip generics - AbstractCompiler.findType finds the raw type anyway
            simpleType = simpleType.substring(0, simpleType.indexOf('<'));
        }

        expectedWrapper = AbstractCompiler.findType(sequence.compilationUnit(), simpleType);

        if (expectedWrapper == null) {
            // If we can't resolve expected type, fallback to basic string match to be safe
            return varWrapper.getName() != null && (varWrapper.getName().equals(expectedType) ||
                    varWrapper.getName().endsWith("." + expectedType));
        }

        // 4. Check compatibility
        return checkCompatibility(varWrapper, expectedWrapper);
    }

    private boolean checkCompatibility(TypeWrapper varWrapper, TypeWrapper expectedWrapper) {
        // Case 1: Both are Reflection types
        if (varWrapper.getClazz() != null && expectedWrapper.getClazz() != null) {
            return expectedWrapper.getClazz().isAssignableFrom(varWrapper.getClazz());
        }

        // Case 2: Both are AST Types or Mixed - Check FQN equality first
        String varFQN = varWrapper.getFullyQualifiedName();
        String expectedFQN = expectedWrapper.getFullyQualifiedName();

        if (varFQN != null && varFQN.equals(expectedFQN)) {
            return true;
        }

        // Case 3: AST Inheritance Check
        // If the variable type is an AST type, we can check its ancestors
        if (varWrapper.getType() != null && varWrapper.getType().isClassOrInterfaceDeclaration()) {
            var typeDecl = varWrapper.getType().asClassOrInterfaceDeclaration();

            // Check extended types (superclasses)
            if (typeDecl.getExtendedTypes() != null) {
                for (var extended : typeDecl.getExtendedTypes()) {
                    String extendedFQN = AbstractCompiler.findFullyQualifiedName(
                            varWrapper.getType().findCompilationUnit().orElse(null), extended);
                    if (extendedFQN != null && extendedFQN.equals(expectedFQN)) {
                        return true;
                    }
                    // Only deep check if we really need to (performance tradeoff)
                    // Ideally we would recursively resolve, but 1-level up + FQN match catches most
                    // direct inheritance
                }
            }

            // Check implemented interfaces
            if (typeDecl.getImplementedTypes() != null) {
                for (var implemented : typeDecl.getImplementedTypes()) {
                    String implFQN = AbstractCompiler.findFullyQualifiedName(
                            varWrapper.getType().findCompilationUnit().orElse(null), implemented);
                    if (implFQN != null && implFQN.equals(expectedFQN)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
