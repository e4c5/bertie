package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.analysis.MutabilityAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that extracts duplicate test setup code into @BeforeEach methods.
 * Promotes local variables to instance fields and creates/merges with existing
 * setUp methods.
 */
public class ExtractBeforeEachRefactorer {

    /**
     * Apply the refactoring to extract duplicate setup code.
     */
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation)
            throws IOException {

        TestClassHelper.TestClassInfo classInfo = TestClassHelper.getAndValidateTestClass(cluster);
        CompilationUnit cu = classInfo.compilationUnit();
        Path sourceFile = classInfo.sourceFile();
        ClassOrInterfaceDeclaration testClass = classInfo.testClass();

        // Extract variables that need to be promoted to fields
        Map<String, String> variablesToPromote = extractVariablesToPromote(cluster.primary());

        // Create or get existing @BeforeEach method
        MethodDeclaration beforeEachMethod = getOrCreateBeforeEachMethod(testClass,
                recommendation.getSuggestedMethodName());

        // Add the setup code to @BeforeEach
        addSetupCode(beforeEachMethod, cluster.primary(), variablesToPromote);

        // Promote variables to instance fields
        promoteVariablesToFields(testClass, variablesToPromote);

        // Remove duplicate code from all test methods
        removeDuplicatesFromTests(cluster, testClass);

        return new RefactoringResult(sourceFile, cu.toString());
    }

    /**
     * Extract variables from the duplicate code that need to be instance fields.
     */
    private Map<String, String> extractVariablesToPromote(
            com.raditha.dedup.model.StatementSequence sequence) {

        Map<String, String> variables = new LinkedHashMap<>();

        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();

                // Check for variable declarations
                if (exprStmt.getExpression().isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDecl = exprStmt.getExpression().asVariableDeclarationExpr();

                    for (VariableDeclarator variable : varDecl.getVariables()) {
                        String varName = variable.getNameAsString();
                        String varType = variable.getType().asString();
                        variables.put(varName, varType);
                    }
                }
            }
        }

        return variables;
    }

    /**
     * Get existing @BeforeEach method with the same name or create new one.
     * Each unique method name gets its own @BeforeEach method.
     */
    private MethodDeclaration getOrCreateBeforeEachMethod(
            ClassOrInterfaceDeclaration testClass, String methodName) {

        // Look for existing @BeforeEach method WITH THE SAME NAME
        Optional<MethodDeclaration> existing = testClass.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("BeforeEach")))
                .findFirst();

        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new @BeforeEach method
        MethodDeclaration beforeEach = testClass.addMethod(methodName, Modifier.Keyword.PRIVATE);
        beforeEach.setType("void");
        beforeEach.addAnnotation(new MarkerAnnotationExpr("BeforeEach"));
        beforeEach.setBody(new BlockStmt());

        // Add import for BeforeEach if not present
        CompilationUnit cu = (CompilationUnit) testClass.getParentNode().get();
        if (cu.getImports().stream()
                .noneMatch(i -> i.getNameAsString().endsWith("BeforeEach"))) {
            cu.addImport("org.junit.jupiter.api.BeforeEach");
        }

        return beforeEach;
    }

    /**
     * Add setup code to the @BeforeEach method.
     */
    private void addSetupCode(MethodDeclaration beforeEachMethod,
            com.raditha.dedup.model.StatementSequence sequence,
            Map<String, String> promotedVariables) {

        BlockStmt body = beforeEachMethod.getBody()
                .orElseGet(() -> {
                    BlockStmt newBody = new BlockStmt();
                    beforeEachMethod.setBody(newBody);
                    return newBody;
                });

        for (Statement stmt : sequence.statements()) {
            Statement newStmt = stmt.clone();

            // Remove variable declaration keywords for promoted variables
            if (newStmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = newStmt.asExpressionStmt();
                if (exprStmt.getExpression().isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDecl = exprStmt.getExpression().asVariableDeclarationExpr();

                    for (VariableDeclarator variable : varDecl.getVariables()) {
                        if (promotedVariables.containsKey(variable.getNameAsString())) {
                            // Convert to assignment: type var = value -> var = value
                            if (variable.getInitializer().isPresent()) {
                                exprStmt.setExpression(
                                        new com.github.javaparser.ast.expr.AssignExpr(
                                                new NameExpr(variable.getNameAsString()),
                                                variable.getInitializer().get(),
                                                com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN));
                            }
                        }
                    }
                }
            }

            body.addStatement(newStmt);
        }
    }

    /**
     * Promote local variables to instance fields.
     * 
     * FIXED Gap 6: Now uses MutabilityAnalyzer to check if types are safe to
     * promote.
     * Only promotes immutable types and mock objects to avoid test isolation
     * issues.
     */
    private void promoteVariablesToFields(ClassOrInterfaceDeclaration testClass,
            Map<String, String> variables) {

        MutabilityAnalyzer mutabilityAnalyzer = new MutabilityAnalyzer();

        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String varName = variable.getKey();
            String varType = variable.getValue();

            // Gap 6 FIX: Skip mutable types - they would break test isolation
            // Only promote immutable types and mocks to instance fields
            if (!mutabilityAnalyzer.isSafeToPromote(varType)) {
                // Don't promote mutable types - keep them local to each test
                continue;
            }

            // Check if field already exists
            boolean fieldExists = testClass.getFields().stream()
                    .flatMap(f -> f.getVariables().stream())
                    .anyMatch(v -> v.getNameAsString().equals(varName));

            if (!fieldExists) {
                testClass.addField(varType, varName, Modifier.Keyword.PRIVATE);
            }
        }
    }

    /**
     * Remove duplicate code from test methods.
     */
    private void removeDuplicatesFromTests(DuplicateCluster cluster,
            ClassOrInterfaceDeclaration testClass) {

        // Remove statements from primary sequence
        removeStatementsFromSequence(cluster.primary(), testClass);

        // Remove statements from duplicate sequences
        cluster.duplicates().forEach(dup ->
            removeStatementsFromSequence(dup.seq2(), testClass)
        );
    }

    /**
     * Remove statements from a specific sequence.
     * Uses startOffset to remove from the correct position.
     */
    private void removeStatementsFromSequence(StatementSequence sequence,
            ClassOrInterfaceDeclaration testClass) {

        String methodName = sequence.containingMethod().getNameAsString();
        int startOffset = sequence.startOffset();
        int count = sequence.statements().size();

        testClass.getMethodsByName(methodName).forEach(method -> {
            method.getBody().ifPresent(body -> {
                NodeList<Statement> statements = body.getStatements();

                // Remove from the ACTUAL position (startOffset), not from index 0!
                // This fixes Gap 9: removes statements at their correct position
                for (int i = 0; i < count && startOffset < statements.size(); i++) {
                    statements.remove(startOffset);
                }
            });
        });
    }

    /**
     * Result of a refactoring operation.
     */
    public record RefactoringResult(Path sourceFile, String refactoredCode) {
    }
}
