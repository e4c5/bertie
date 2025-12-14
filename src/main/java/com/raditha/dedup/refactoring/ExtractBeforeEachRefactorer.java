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
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;

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

        CompilationUnit cu = cluster.primary().compilationUnit();
        Path sourceFile = cluster.primary().sourceFilePath();

        // Get the test class
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No class found"));

        // Validate this is a test class
        if (!isTestClass(testClass)) {
            throw new IllegalArgumentException("Not a test class: " + testClass.getNameAsString());
        }

        // Extract variables that need to be promoted to fields
        Map<String, String> variablesToPromote = extractVariablesToPromote(cluster.primary());

        // Create or get existing @BeforeEach method
        MethodDeclaration beforeEachMethod = getOrCreateBeforeEachMethod(testClass,
                recommendation.suggestedMethodName());

        // Add the setup code to @BeforeEach
        addSetupCode(beforeEachMethod, cluster.primary(), variablesToPromote);

        // Promote variables to instance fields
        promoteVariablesToFields(testClass, variablesToPromote);

        // Remove duplicate code from all test methods
        removeDuplicatesFromTests(cluster, testClass);

        return new RefactoringResult(sourceFile, cu.toString());
    }

    /**
     * Check if this is a test class.
     */
    private boolean isTestClass(ClassOrInterfaceDeclaration clazz) {
        // Check for JUnit annotations on methods
        return clazz.getMethods().stream()
                .anyMatch(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test")));
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

                    for (VariableDeclarator var : varDecl.getVariables()) {
                        String varName = var.getNameAsString();
                        String varType = var.getType().asString();
                        variables.put(varName, varType);
                    }
                }
            }
        }

        return variables;
    }

    /**
     * Get existing @BeforeEach method or create new one.
     */
    private MethodDeclaration getOrCreateBeforeEachMethod(
            ClassOrInterfaceDeclaration testClass, String methodName) {

        // Look for existing @BeforeEach method
        Optional<MethodDeclaration> existing = testClass.getMethods().stream()
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

                    for (VariableDeclarator var : varDecl.getVariables()) {
                        if (promotedVariables.containsKey(var.getNameAsString())) {
                            // Convert to assignment: type var = value -> var = value
                            if (var.getInitializer().isPresent()) {
                                exprStmt.setExpression(
                                        new com.github.javaparser.ast.expr.AssignExpr(
                                                new NameExpr(var.getNameAsString()),
                                                var.getInitializer().get(),
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
     */
    private void promoteVariablesToFields(ClassOrInterfaceDeclaration testClass,
            Map<String, String> variables) {

        for (Map.Entry<String, String> var : variables.entrySet()) {
            String varName = var.getKey();
            String varType = var.getValue();

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

        // Get all affected method names
        Set<String> affectedMethods = new HashSet<>();
        affectedMethods.add(cluster.primary().containingMethod().getNameAsString());

        cluster.duplicates().forEach(dup -> {
            affectedMethods.add(dup.seq2().containingMethod().getNameAsString());
        });

        // Remove the duplicate statements from each method
        for (String methodName : affectedMethods) {
            testClass.getMethodsByName(methodName).forEach(method -> {
                method.getBody().ifPresent(body -> {
                    // Remove the first N statements (the duplicated setup code)
                    int statementsToRemove = cluster.primary().statements().size();
                    NodeList<Statement> statements = body.getStatements();

                    for (int i = 0; i < statementsToRemove && i < statements.size(); i++) {
                        statements.remove(0);
                    }
                });
            });
        }
    }

    /**
     * Result of a refactoring operation.
     */
    public record RefactoringResult(Path sourceFile, String refactoredCode) {
    }
}
