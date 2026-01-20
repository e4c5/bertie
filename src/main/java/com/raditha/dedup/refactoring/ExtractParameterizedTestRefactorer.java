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
import java.util.stream.Collectors;

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

        // Filter instances to ensure consistency (robustness against partial matches)
        // Group instances by parameter count to find the dominant pattern
        Map<Integer, List<TestInstance>> byParamCount = testInstances.stream()
                .collect(Collectors.groupingBy(i -> i.literals.size()));



        // Select the largest group (most common parameter count)
        testInstances = byParamCount.values().stream()
                .max(Comparator.comparingInt(List::size))
                .orElse(List.of());
        
        // Safety Check: Do not attempt to parameterize a method that is already a ParameterizedTest.
        // This prevents Pass 2 from destructively merging distinct parameterized tests created in Pass 1.
        boolean anyAlreadyParameterized = testInstances.stream()
                .anyMatch(inst -> inst.method.getAnnotationByName("ParameterizedTest").isPresent());
        
        if (anyAlreadyParameterized) {
             throw new IllegalStateException("Cannot recursively parameterize existing @ParameterizedTest methods.");
        }

        if (testInstances.isEmpty()) {
             // No instances selected. Aborting.
        }

        if (testInstances.size() < 3) {
            throw new IllegalArgumentException(
                    "Need at least 3 similar tests for parameterization, found: " + testInstances.size());
        }

        // Determine parameter types and names
        List<ParameterInfo> parameters = analyzeParameters(testInstances);

        // Create the parameterized test method
        // Use the first consistent instance as the template to ensure body matches parameters
        MethodDeclaration checkMethod = testInstances.get(0).method;

        MethodDeclaration parameterizedMethod = createParameterizedMethod(
                checkMethod,
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
     * 
     * <p><b>Important:</b> This method uses {@link DuplicateCluster#allSequences()} 
     * which returns a stream of unique {@link StatementSequence} objects (deduplicated by range/file).
     * 
     * <p>Using {@code allSequences()} is critical for two reasons:
     * <ol>
     *   <li><b>Deduplication:</b> It filters out duplicate occurrences of the same code block found in pairs,
     *       preventing duplicate entries in the {@code @CsvSource}.</li>
     *   <li><b>Correctness:</b> It provides the exact {@code StatementSequence} matched by the clusterer,
     *       which contains the specific list of statements ({@code seq.statements()}). 
     *       Attempting to re-parse the whole method body (e.g., via {@code seq.containingMethod().getBody()})
     *       is incorrect because it may include extra statements not part of the match, leading to 
     *       "Inconsistent literal counts" errors during parameterization.</li>
     * </ol>
     * 
     * @param cluster The duplicate cluster containing similar test methods
     * @return List of unique test instances, one per unique sequence
     */
    private List<TestInstance> extractTestInstances(DuplicateCluster cluster) {
        return cluster.allSequences().stream()
            .map(seq -> new TestInstance(
                seq.containingMethod(),
                extractLiterals(seq.statements())))
            .toList();
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
            
            // Logic to replace literals with parameter names
            // We search for the sequence of literals that matches the *template instance's* values
            // This handles partial matches where the method body contains more literals than the parameters
            List<LiteralExpr> bodyLiterals = newBody.findAll(LiteralExpr.class);
            List<LiteralValue> targetLiterals = instances.get(0).literals;

            int matchIndex = findLiteralSequenceIndex(bodyLiterals, targetLiterals);
            
            if (matchIndex != -1) {
                for (int i = 0; i < targetLiterals.size(); i++) {
                    LiteralExpr literal = bodyLiterals.get(matchIndex + i);
                    ParameterInfo param = parameters.get(i);
                    literal.replace(new NameExpr(param.name));
                }
            } else if (bodyLiterals.size() == parameters.size()) {
                // Fallback: exact size match (legacy behavior)
                 for (int i = 0; i < bodyLiterals.size(); i++) {
                    bodyLiterals.get(i).replace(new NameExpr(parameters.get(i).name));
                }
            } else {
                 // Could not find safe match. Parameters might be unused, but valid body is preserved.
                 // This usually indicates a misalignment between the cluster sequence and the method body.
            }
            
            method.setBody(newBody);
        }

        return method;
    }

    /**
     * Find the starting index of targetLiterals sequence within bodyLiterals.
     */
    private int findLiteralSequenceIndex(List<LiteralExpr> bodyLiterals, List<LiteralValue> targetLiterals) {
        if (targetLiterals.isEmpty()) return -1;
        
        for (int i = 0; i <= bodyLiterals.size() - targetLiterals.size(); i++) {
            boolean match = true;
            for (int j = 0; j < targetLiterals.size(); j++) {
                LiteralExpr bodyLit = bodyLiterals.get(i + j);
                LiteralValue targetLit = targetLiterals.get(j);
                
                // Compare value and type
                // Note: bodyLit.toString() includes quotes for strings, targetLit.value might/might not
                // We use our helper method to determine type and clean value
                String bodyValue = bodyLit.toString();
                String bodyType = determineLiteralType(bodyLit);
                
                if (!bodyType.equals(targetLit.type) || !bodyValue.equals(targetLit.value)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
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
                rows.stream().distinct().collect(Collectors.joining(",\n        ")) +
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
