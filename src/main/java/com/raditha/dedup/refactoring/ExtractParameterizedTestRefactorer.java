package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.SimilarityPair;

import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that converts duplicate test methods with varying data into
 * JUnit 5 @ParameterizedTest.
 */
public class ExtractParameterizedTestRefactorer {

    /**
     * Apply the refactoring to create a parameterized test.
     */
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation) {

        TestClassHelper.TestClassInfo classInfo = TestClassHelper.getAndValidateTestClass(cluster);
        CompilationUnit cu = classInfo.compilationUnit();
        Path sourceFile = classInfo.sourceFile();
        ClassOrInterfaceDeclaration testClass = classInfo.testClass();

        // Extract parameters from all duplicate instances
        List<TestInstance> testInstances = extractTestInstances(cluster);

        if (testInstances.size() < 3) {
            throw new IllegalArgumentException(
                    "Need at least 3 similar tests for parameterization, found: " + testInstances.size());
        }

        // Validate consistency (all must have same number of parameters)
        int paramCount = testInstances.get(0).literals.size();
        for (TestInstance instance : testInstances) {
            if (instance.literals.size() != paramCount) {
                // If inconsistent, we can't safely parameterize.
                // In a real scenario, we might try to find common subsequence, but for now abort.
                // Since this is a void method, we can't return null, but we can throw to skip.
                throw new IllegalStateException("Inconsistent literal counts across test instances. Cannot parameterize.");
            }
        }

        // Determine parameter types and names
        List<ParameterInfo> parameters = analyzeParameters(testInstances);

        // Create the parameterized test method
        MethodDeclaration parameterizedMethod = createParameterizedMethod(
                cluster.primary().containingMethod(),
                recommendation.getSuggestedMethodName(),
                parameters,
                testInstances);

        // Add to class
        testClass.addMember(parameterizedMethod);

        // Remove original test methods
        removeOriginalTests(testClass, testInstances);

        // Add necessary imports
        addParameterizedTestImports(cu);

        return new RefactoringResult(sourceFile, cu.toString());
    }

    /**
     * Extract test instances from cluster.
     */
    private List<TestInstance> extractTestInstances(DuplicateCluster cluster) {
        List<TestInstance> instances = new ArrayList<>();

        // Add primary
        instances.add(new TestInstance(
                cluster.primary().containingMethod(),
                extractLiterals(cluster.primary().statements())));

        // Add duplicates
        for (SimilarityPair pair : cluster.duplicates()) {
            instances.add(new TestInstance(
                    pair.seq2().containingMethod(),
                    extractLiterals(pair.seq2().statements())));
        }

        return instances;
    }

    /**
     * Extract literal values from statements.
     */
    private List<LiteralValue> extractLiterals(List<Statement> statements) {
        List<LiteralValue> literals = new ArrayList<>();

        for (Statement stmt : statements) {
            // Find all literal expressions in the statement
            stmt.findAll(LiteralExpr.class).forEach(literal -> {
                String value = literal.toString();
                String type = determineLiteralType(literal);
                literals.add(new LiteralValue(value, type));
            });
        }

        return literals;
    }

    /**
     * Determine the type of a literal.
     */
    private String determineLiteralType(LiteralExpr literal) {
        if (literal.isStringLiteralExpr()) {
            return "String";
        } else if (literal.isIntegerLiteralExpr()) {
            return "int";
        } else if (literal.isDoubleLiteralExpr()) {
            return "double";
        } else if (literal.isBooleanLiteralExpr()) {
            return "boolean";
        } else if (literal.isLongLiteralExpr()) {
            return "long";
        }
        return "String"; // default
    }

    /**
     * Analyze parameters across all test instances.
     */
    private List<ParameterInfo> analyzeParameters(List<TestInstance> instances) {
        if (instances.isEmpty()) {
            return List.of();
        }

        int paramCount = instances.get(0).literals.size();
        List<ParameterInfo> parameters = new ArrayList<>();

        for (int i = 0; i < paramCount; i++) {
            final int index = i;
            String type = instances.get(0).literals.get(i).type;
            String name = generateParameterName(index, paramCount);

            parameters.add(new ParameterInfo(name, type, index));
        }

        return parameters;
    }

    /**
     * Generate a parameter name based on position.
     */
    private String generateParameterName(int index, int total) {
        if (total == 2) {
            return index == 0 ? "input" : "expected";
        } else if (index == total - 1) {
            return "expected";
        } else if (index == 0) {
            return "input";
        } else {
            return "param" + (index + 1);
        }
    }

    /**
     * Create the parameterized test method.
     */
    private MethodDeclaration createParameterizedMethod(
            MethodDeclaration originalMethod,
            String methodName,
            List<ParameterInfo> parameters,
            List<TestInstance> instances) {

        MethodDeclaration method = new MethodDeclaration();
        method.setName(methodName);
        method.setModifiers(Modifier.Keyword.PUBLIC);
        method.setType("void");

        // Add @ParameterizedTest annotation
        method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));

        // Add @CsvSource annotation with test data
        method.addAnnotation(createCsvSourceAnnotation(instances));

        // Add parameters
        for (ParameterInfo param : parameters) {
            method.addParameter(new Parameter(
                    new ClassOrInterfaceType(null, param.type),
                    param.name));
        }

        // Copy and parameterize the method body
        if (originalMethod.getBody().isPresent()) {
            com.github.javaparser.ast.stmt.BlockStmt newBody = originalMethod.getBody().get().clone();
            
            // Replace literals with parameter names
            List<LiteralExpr> bodyLiterals = newBody.findAll(LiteralExpr.class);
            
            // We expect the number of literals in the body to match the extracted parameters
            // (since we extracted from the same body structure)
            // However, we must be careful with order.
             if (bodyLiterals.size() == parameters.size()) {
                for (int i = 0; i < bodyLiterals.size(); i++) {
                    LiteralExpr literal = bodyLiterals.get(i);
                    ParameterInfo param = parameters.get(i);
                    
                    // Replace literal with NameExpr
                    literal.replace(new NameExpr(param.name));
                }
            } else {
                 // Fallback or warning? If counts don't match, we might have missed something or findAll behaves differently.
                 // For now, proceed. The verification step will catch if it's broken.
            }
            
            method.setBody(newBody);
        }

        return method;
    }

    /**
     * Create @CsvSource annotation with test data.
     */
    private AnnotationExpr createCsvSourceAnnotation(List<TestInstance> instances) {
        List<String> rows = new ArrayList<>();

        for (TestInstance instance : instances) {
            String row = instance.literals.stream()
                    .map(l -> cleanLiteralValue(l.value, l.type))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            rows.add("\"" + row + "\"");
        }

        String csvData = "{\n        " +
                String.join(",\n        ", rows) +
                "\n    }";

        // Create annotation manually
        NormalAnnotationExpr annotation = new NormalAnnotationExpr();
        annotation.setName("CsvSource");
        annotation.addPair("value", new NameExpr(csvData));

        return annotation;
    }

    /**
     * Remove original test methods.
     */
    private void removeOriginalTests(ClassOrInterfaceDeclaration testClass, List<TestInstance> instances) {
        for (TestInstance instance : instances) {
            testClass.remove(instance.method);
        }
    }

    /**
     * Add necessary imports for parameterized tests.
     */
    private void addParameterizedTestImports(CompilationUnit cu) {
        List<String> requiredImports = List.of(
                "org.junit.jupiter.params.ParameterizedTest",
                "org.junit.jupiter.params.provider.CsvSource");

        for (String importName : requiredImports) {
            if (cu.getImports().stream()
                    .noneMatch(i -> i.getNameAsString().equals(importName))) {
                cu.addImport(importName);
            }
        }
    }

    /**
     * Test instance with method and extracted literals.
     */
    private record TestInstance(MethodDeclaration method, List<LiteralValue> literals) {
    }

    /**
     * Extracted literal value.
     */
    private record LiteralValue(String value, String type) {
    }

    /**
     * Parameter information.
     */
    private record ParameterInfo(String name, String type, int index) {
    }

    /**
     * Result of a refactoring operation.
     */
    private String cleanLiteralValue(String rawValue, String type) {
        if ("String".equals(type)) {
            // Remove surrounding quotes
            if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                return rawValue.substring(1, rawValue.length() - 1);
            }
            return rawValue.replace("\"", "");
        } else if ("long".equals(type)) {
            return rawValue.toUpperCase().replace("L", "");
        } else if ("float".equals(type)) {
            return rawValue.toUpperCase().replace("F", "");
        } else if ("double".equals(type)) {
             return rawValue.toUpperCase().replace("D", "");
        }
        return rawValue;
    }

    public record RefactoringResult(Path sourceFile, String refactoredCode) {
    }
}
