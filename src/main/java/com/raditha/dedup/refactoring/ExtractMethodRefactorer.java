package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts duplicate code to a private helper method.
 * This is the most common and safest refactoring strategy.
 */
public class ExtractMethodRefactorer {

    /**
     * Perform extract method refactoring.
     */
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation)
            throws IOException {

        StatementSequence primary = cluster.primary();

        // 1. Create the new helper method
        MethodDeclaration helperMethod = createHelperMethod(primary, recommendation);

        // 2. Add method to the class
        ClassOrInterfaceDeclaration containingClass = primary.containingMethod()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No containing class found"));

        containingClass.addMember(helperMethod);

        // 3. Replace all duplicate occurrences with method calls
        for (SimilarityPair pair : cluster.duplicates()) {
            replaceWithMethodCall(pair.seq1(), recommendation);
            replaceWithMethodCall(pair.seq2(), recommendation);
        }

        // 4. Write the modified compilation unit back to file
        String refactoredCode = primary.compilationUnit().toString();
        Path sourceFile = primary.sourceFilePath();

        // Create result
        return new RefactoringResult(
                sourceFile,
                refactoredCode,
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
        method.setModifiers(Modifier.Keyword.PRIVATE);

        // Set return type
        String returnType = recommendation.suggestedReturnType();
        method.setType(returnType != null ? returnType : "void");

        // Add parameters
        for (ParameterSpec param : recommendation.suggestedParameters()) {
            method.addParameter(new Parameter(
                    new ClassOrInterfaceType(null, param.type()),
                    param.name()));
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
     * Find the variable to return (heuristic - finds variable of matching type).
     */
    private String findReturnVariable(StatementSequence sequence, String returnType) {
        // Simple heuristic: look for variable declarations of the return type
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                Expression expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
                    if (varDecl.getVariables().size() > 0) {
                        var variable = varDecl.getVariable(0);
                        String varType = variable.getType().asString();
                        if (varType.contains(returnType) || returnType.contains(varType)) {
                            return variable.getNameAsString();
                        }
                    }
                }
            }
        }
        return null;
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
            // Use example values to find actual arguments
            if (!param.exampleValues().isEmpty()) {
                String example = param.exampleValues().get(0);
                arguments.add(new NameExpr(example));
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

        Statement firstStmt = sequence.statements().get(0);
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
     * Result of a refactoring operation.
     */
    public record RefactoringResult(
            Path sourceFile,
            String refactoredCode,
            RefactoringStrategy strategy,
            String description) {
        /**
         * Write the refactored code to file.
         */
        public void apply() throws IOException {
            Files.writeString(sourceFile, refactoredCode);
        }
    }
}
