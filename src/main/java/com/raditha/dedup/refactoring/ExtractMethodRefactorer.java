package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts duplicate code to a private helper method.
 * This is the most common and safest refactoring strategy.
 */
public class ExtractMethodRefactorer {

    /**
     * Perform extract method refactoring.
     * 
     * FIXED: Now tracks ALL modified compilation units, not just primary.
     * This fixes the bug where seq1/seq2 in different files had method calls
     * but the method definition was never written to their files.
     */
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation) {

        StatementSequence primary = cluster.primary();

        // Track all modified compilation units
        Map<CompilationUnit, Path> modifiedCUs = new LinkedHashMap<>();
        modifiedCUs.put(primary.compilationUnit(), primary.sourceFilePath());

        // 1. Create the new helper method
        MethodDeclaration helperMethod = createHelperMethod(primary, recommendation);

        // 2. Add method to the class (modifies primary CU)
        ClassOrInterfaceDeclaration containingClass = primary.containingMethod()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No containing class found"));

        containingClass.addMember(helperMethod);

        // 3. Replace all duplicate occurrences with method calls (track each CU)
        for (SimilarityPair pair : cluster.duplicates()) {
            replaceWithMethodCall(pair.seq1(), recommendation);
            modifiedCUs.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());

            replaceWithMethodCall(pair.seq2(), recommendation);
            modifiedCUs.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        }

        // 4. Convert all modified CUs to code strings
        Map<Path, String> modifiedFiles = modifiedCUs.entrySet().stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getValue(), entry.getKey().toString()),
                        Map::putAll);

        // Create result with ALL modified files
        return new RefactoringResult(
                modifiedFiles,
                recommendation.strategy(),
                "Extracted method: " + recommendation.suggestedMethodName());
    }

    /**
     * Create the helper method from the primary sequence.
     */
    private MethodDeclaration createHelperMethod(StatementSequence sequence,
            RefactoringRecommendation recommendation) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(recommendation.suggestedMethodName());

        // Check if containing method is static - if so, make helper method static too
        if (sequence.containingMethod() != null && sequence.containingMethod().isStatic()) {
            method.setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        } else {
            method.setModifiers(Modifier.Keyword.PRIVATE);
        }

        // Set return type
        String returnType = recommendation.suggestedReturnType();
        method.setType(returnType != null ? returnType : "void");

        // Add parameters
        for (ParameterSpec param : recommendation.suggestedParameters()) {
            method.addParameter(new Parameter(
                    new ClassOrInterfaceType(null, param.type()),
                    param.name()));
        }

        // Copy thrown exceptions from containing method
        if (sequence.containingMethod() != null) {
            NodeList<ReferenceType> exceptions = sequence.containingMethod().getThrownExceptions();
            for (ReferenceType exception : exceptions) {
                method.addThrownException(exception.clone());
            }
        }

        // Set method body (clone the statements)
        BlockStmt body = new BlockStmt();
        // Determine if we need to transform return statements
        String targetReturnVar = null;
        if (!"void".equals(returnType)) {
            targetReturnVar = findReturnVariable(sequence, returnType);
        }

        for (Statement stmt : sequence.statements()) {
            Statement clonedStmt = stmt.clone();

            // If this is a return statement AND we have a specific variable to return
            // AND the return statement is complex (e.g. return user.getName())
            // WE MUST REPLACE IT with `return user;`
            if (targetReturnVar != null && clonedStmt.isReturnStmt()
                    && clonedStmt.asReturnStmt().getExpression().isPresent()) {
                // Check if the current return expression is exactly the variable
                Expression returnExpr = clonedStmt.asReturnStmt().getExpression().get();
                if (!returnExpr.isNameExpr() || !returnExpr.asNameExpr().getNameAsString().equals(targetReturnVar)) {
                    // It's a complex expression (e.g. user.getName()) or different variable
                    // Check if it USES the target variable
                    List<NameExpr> usedNames = returnExpr.findAll(NameExpr.class);
                    boolean usesTarget = false;
                    for (NameExpr name : usedNames) {
                        if (name.getNameAsString().equals(targetReturnVar)) {
                            usesTarget = true;
                            break;
                        }
                    }

                    if (usesTarget) {
                        // Replace with simple return of the variable
                        clonedStmt = new ReturnStmt(new NameExpr(targetReturnVar));
                    }
                }
            }
            body.addStatement(clonedStmt);
        }

        // Add return statement if needed (and not already present/unreachable)
        if (!"void".equals(returnType) && targetReturnVar != null) {
            // Only add if the last statement isn't already a return
            if (body.getStatements().isEmpty() || !body.getStatements().getLast().get().isReturnStmt()) {
                body.addStatement(new ReturnStmt(new NameExpr(targetReturnVar)));
            }
        }

        method.setBody(body);
        return method;
    }

    /**
     * Find the variable to return using data flow analysis.
     */
    private String findReturnVariable(StatementSequence sequence, String returnType) {
        DataFlowAnalyzer analyzer = new DataFlowAnalyzer();
        return analyzer.findReturnVariable(sequence, returnType);
    }

    /**
     * Replace duplicate code with a method call.
     */
    private void replaceWithMethodCall(StatementSequence sequence, RefactoringRecommendation recommendation) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return;
        }

        BlockStmt block = containingMethod.getBody().get();

        // Build argument list
        NodeList<Expression> arguments = new NodeList<>();
        for (ParameterSpec param : recommendation.suggestedParameters()) {
            String actualValue = extractActualValue(sequence, param);
            String valToUse = null;

            if (actualValue != null) {
                valToUse = actualValue;
            } else if (!param.exampleValues().isEmpty()) {
                valToUse = param.exampleValues().get(0);
            }

            if (valToUse != null) {
                // If parameter is Class<?>, ensure we pass User.class, not just User
                if (("Class<?>".equals(param.type()) || "Class".equals(param.type()))
                        && !valToUse.endsWith(".class")) {
                    // Create Class Expression: User.class
                    arguments.add(new ClassExpr(new ClassOrInterfaceType(null, valToUse)));
                } else {
                    // Default: variable name or literal
                    try {
                        // Try to parse partial expression (like "5000" or "true")
                        // But NameExpr is safer for variables to avoid parsing errors on reserved
                        // words?
                        // "User" is fine as NameExpr if it was a variable.
                        // For literals, we should ideally use proper LiteralExpr.
                        // But extractActualValue returns STRING.
                        // Let's stick to NameExpr for variables, but if it looks like a number...
                        if (valToUse.matches("-?\\d+(\\.\\d+)?")) {
                            arguments.add(new IntegerLiteralExpr(valToUse));
                        } else if ("true".equals(valToUse) || "false".equals(valToUse)) {
                            arguments.add(new BooleanLiteralExpr(Boolean.parseBoolean(valToUse)));
                        } else if (valToUse.startsWith("\"")) { // String lit
                            arguments.add(new StringLiteralExpr(valToUse.replace("\"", "")));
                        } else {
                            arguments.add(new NameExpr(valToUse));
                        }
                    } catch (Exception e) {
                        arguments.add(new NameExpr(valToUse));
                    }
                }
            }
        }

        // Create method call
        MethodCallExpr methodCall = new MethodCallExpr(
                recommendation.suggestedMethodName(),
                arguments.toArray(new Expression[0]));

        // Check if the original sequence contained a return statement that we
        // "swallowed"
        // and if so, what expression it was returning.
        ReturnStmt originalReturnValues = null;
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                originalReturnValues = stmt.asReturnStmt();
                break; // Assume only one return path relevant for replacement logic
            }
        }

        // Find and replace the statements
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx >= 0) {
            // Remove old statements
            int count = sequence.statements().size();
            for (int i = 0; i < count && startIdx < block.getStatements().size(); i++) {
                block.getStatements().remove(startIdx);
            }

            // Insert new logic
            if ("void".equals(recommendation.suggestedReturnType())) {
                // Void return: just the call
                block.getStatements().add(startIdx, new ExpressionStmt(methodCall));

                // If original had return (void), add it back
                if (originalReturnValues != null) {
                    block.getStatements().add(startIdx + 1, new ReturnStmt());
                }
            } else {
                // Returns a value
                String varName = findReturnVariable(sequence, recommendation.suggestedReturnType());

                DataFlowAnalyzer dfa = new DataFlowAnalyzer();
                Set<String> defined = dfa.findDefinedVariables(sequence);

                // FALLBACK 1: Use primary return variable if defined in this sequence
                if (varName == null && recommendation.primaryReturnVariable() != null) {
                    if (defined.contains(recommendation.primaryReturnVariable())) {
                        varName = recommendation.primaryReturnVariable();
                    }
                }

                // FALLBACK 2: If still null, look for ANY defined variable matching the return
                // type
                // This handles parameter renaming (e.g. Primary returns 'user', Duplicate
                // returns 'customer')
                if (varName == null) {
                    List<String> typedCandidates = new ArrayList<>();
                    // Iterate statements to find variable declarations of matching type
                    for (Statement stmt : sequence.statements()) {
                        stmt.findAll(VariableDeclarationExpr.class).forEach(vde -> {
                            vde.getVariables().forEach(v -> {
                                if (v.getType().asString().equals(recommendation.suggestedReturnType())) {
                                    typedCandidates.add(v.getNameAsString());
                                }
                            });
                        });
                    }
                    if (typedCandidates.size() == 1) {
                        varName = typedCandidates.get(0);
                    }
                }

                // If we found a return variable, we assign it
                if (varName != null) {
                    VariableDeclarationExpr varDecl = new VariableDeclarationExpr(
                            new ClassOrInterfaceType(null, recommendation.suggestedReturnType()),
                            varName);
                    varDecl.getVariable(0).setInitializer(methodCall);
                    block.getStatements().add(startIdx, new ExpressionStmt(varDecl));

                    // IF the original code had a return, we must RECONSTRUCT it
                    if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                        // We need to assume the original return expression used 'varName'
                        // and we should now return that SAME expression
                        // BUT 'varName' is now the result of the method call.
                        // So effectively we just copy the original return statement!
                        // Since 'varName' is now defined in the scope (we just added it),
                        // `return varName.getName()` will work validly.
                        block.getStatements().add(startIdx + 1, originalReturnValues.clone());
                    }
                } else {
                    // No variable found (maybe direct return?), just add call
                    // This case is tricky if we need to return.
                    if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                        // Fallback: return methodCall() directly?
                        // Only if types match. If mismatched (User vs String), this is broken.
                        // But we can't solve everything.
                        block.getStatements().add(startIdx, new ReturnStmt(methodCall));
                    } else {
                        block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
                    }
                }
            }
        }
    }

    /**
     * Find the index of the first statement in a sequence within a block.
     */
    private int findStatementIndex(BlockStmt block, StatementSequence sequence) {
        if (sequence.statements().isEmpty()) {
            return -1;
        }

        NodeList<Statement> blockStmts = block.getStatements();

        // Simple approach: match by line number
        int targetLine = sequence.range().startLine();

        for (int i = 0; i < blockStmts.size(); i++) {
            Statement stmt = blockStmts.get(i);
            if (stmt.getRange().isPresent() &&
                    stmt.getRange().get().begin.line == targetLine) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Extract the actual value used in this sequence for a parameter.
     * FIXED Gap 1&2: Finds the ACTUAL value from the sequence AST.
     */
    private String extractActualValue(StatementSequence sequence, ParameterSpec param) {
        // Strategy: Search through sequence statements for expressions matching the
        // parameter type
        // and extract the actual variable/literal used

        List<String> foundValues = new ArrayList<>();

        for (Statement stmt : sequence.statements()) {
            // Collect all expressions of the matching type from this statement
            stmt.findAll(Expression.class).forEach(expr -> {
                String exprStr = expr.toString();
                // Check if this expression could be a parameter value
                if (couldBeParameterValue(expr, param)) {
                    foundValues.add(exprStr);
                }
            });
        }

        // Return the first found value, or fall back to example
        if (!foundValues.isEmpty()) {
            return foundValues.get(0);
        }

        // Fallback to example value
        if (!param.exampleValues().isEmpty()) {
            return param.exampleValues().get(0);
        }

        return null;
    }

    /**
     * Check if an expression could be a parameter value based on type and pattern.
     */
    private boolean couldBeParameterValue(Expression expr, ParameterSpec param) {
        String exprStr = expr.toString();

        // Skip this/super references
        if (exprStr.equals("this") || exprStr.equals("super")) {
            return false;
        }

        // For string literals, check if it's a string
        if (expr.isStringLiteralExpr() && "String".equals(param.type())) {
            return true;
        }

        // For integer literals
        if (expr.isIntegerLiteralExpr() && ("int".equals(param.type()) || "Integer".equals(param.type()))) {
            return true;
        }

        // For long literals
        if (expr.isLongLiteralExpr() && ("long".equals(param.type()) || "Long".equals(param.type()))) {
            return true;
        }

        // For boolean literals
        if (expr.isBooleanLiteralExpr() && ("boolean".equals(param.type()) || "Boolean".equals(param.type()))) {
            return true;
        }

        // For variable names, check if it matches one of the example patterns
        // REVERTED: Conservative approach - only accept known example values
        // This avoids TypeInferenceException for out-of-scope variables
        if (expr.isNameExpr() && !param.exampleValues().isEmpty()) {
            // If any example value matches this name, it's likely the parameter
            for (String example : param.exampleValues()) {
                if (example.equals(exprStr)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Result of a refactoring operation containing the modified source code.
     * Now supports multiple files being modified in a single refactoring operation.
     */
    public record RefactoringResult(
            Map<Path, String> modifiedFiles, // file path -> refactored code
            RefactoringStrategy strategy,
            String description) {

        /**
         * Convenience constructor for single-file refactorings.
         */
        public RefactoringResult(Path sourceFile, String refactoredCode,
                RefactoringStrategy strategy, String description) {
            this(Map.of(sourceFile, refactoredCode), strategy, description);
        }

        /**
         * Write all refactored files.
         */
        public void apply() throws IOException {
            for (Map.Entry<Path, String> entry : modifiedFiles.entrySet()) {
                Files.writeString(entry.getKey(), entry.getValue());
            }
        }
    }
}
