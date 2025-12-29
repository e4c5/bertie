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
        for (Statement stmt : sequence.statements()) {
            body.addStatement(stmt.clone());
        }

        // Add return statement if needed
        if (!"void".equals(returnType)) {
            // Try to find the variable we should return
            String varToReturn = findReturnVariable(sequence, returnType);
            if (varToReturn != null) {
                body.addStatement(new ReturnStmt(new NameExpr(varToReturn)));
            }
        }

        method.setBody(body);
        return method;
    }

    /**
     * Find the variable to return using data flow analysis.
     * 
     * FIXED Gap 5: Now uses DataFlowAnalyzer to find variables that are:
     * - Defined in the sequence
     * - Actually used AFTER the sequence
     * - Match the expected return type
     * 
     * This ensures we return the CORRECT variable, not just the first one.
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
        // FIXED Gap 1&2: Extract actual values from THIS sequence, not example values
        NodeList<Expression> arguments = new NodeList<>();
        for (ParameterSpec param : recommendation.suggestedParameters()) {
            // Extract the ACTUAL value used in this specific sequence
            String actualValue = extractActualValue(sequence, param);
            if (actualValue != null) {
                arguments.add(new NameExpr(actualValue));
            } else if (!param.exampleValues().isEmpty()) {
                // Fallback to example value if extraction fails
                arguments.add(new NameExpr(param.exampleValues().get(0)));
            }
        }

        // Create method call
        MethodCallExpr methodCall = new MethodCallExpr(
                recommendation.suggestedMethodName(),
                arguments.toArray(new Expression[0]));

        // Find and replace the statements
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx >= 0) {
            // Remove old statements
            int count = sequence.statements().size();
            for (int i = 0; i < count && startIdx < block.getStatements().size(); i++) {
                block.getStatements().remove(startIdx);
            }

            // Insert method call
            if ("void".equals(recommendation.suggestedReturnType())) {
                block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
            } else {
                // If method returns something, create assignment
                String varName = findReturnVariable(sequence, recommendation.suggestedReturnType());
                if (varName != null) {
                    VariableDeclarationExpr varDecl = new VariableDeclarationExpr(
                            new ClassOrInterfaceType(null, recommendation.suggestedReturnType()),
                            varName);
                    varDecl.getVariable(0).setInitializer(methodCall);
                    block.getStatements().add(startIdx, new ExpressionStmt(varDecl));
                } else {
                    block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
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

        // For boolean literals
        if (expr.isBooleanLiteralExpr() && ("boolean".equals(param.type()) || "Boolean".equals(param.type()))) {
            return true;
        }

        // For variable names, check if it matches one of the example patterns
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
