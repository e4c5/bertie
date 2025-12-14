package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExtractUtilityClassRefactorer - extracting duplicate methods into
 * utility classes.
 */
class ExtractUtilityClassRefactorerTest {

    private ExtractUtilityClassRefactorer refactorer;

    @BeforeEach
    void setUp() {
        refactorer = new ExtractUtilityClassRefactorer();
    }

    @Test
    void testBasicUtilityClassGeneration() throws IOException {
        String code = """
                package com.example.service;

                public class UserService {
                    private boolean isValidEmail(String email) {
                        return email != null && email.matches("^[A-Za-z0-9+_.-]+@(.+)$");
                    }

                    public void processUser(String email) {
                        if (isValidEmail(email)) {
                            // process
                        }
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "isValidEmail");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "isValidEmail",
                List.of(),
                "boolean",
                "com.example.service.util",
                0.95,
                10);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        assertNotNull(result);
        String utilityCode = result.refactoredCode();

        // Should generate utility class
        assertTrue(utilityCode.contains("class ValidationUtils"), "Should create ValidationUtils");
        assertTrue(utilityCode.contains("public static boolean isValidEmail"), "Should make method static");
        assertTrue(utilityCode.contains("private ValidationUtils()"), "Should have private constructor");
    }

    @Test
    void testUtilityClassNaming() throws IOException {
        String code = """
                package com.example;

                public class Helper {
                    private String formatString(String s) {
                        return s.trim().toUpperCase();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "formatString");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "formatString",
                List.of(),
                "String",
                "com.example.util",
                0.90,
                8);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        // Should name utility class based on method name
        assertTrue(result.utilityClassName().contains("Utils"),
                "Utility class name should contain 'Utils'");
    }

    @Test
    void testPackageDeclaration() throws IOException {
        String code = """
                package com.example.service;

                public class Calculator {
                    private int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "add");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "add",
                List.of(),
                "int",
                "com.example.service.util",
                0.88,
                6);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String utilityCode = result.refactoredCode();

        // Should have .util package
        assertTrue(utilityCode.contains("package com.example.service.util"),
                "Should place in util package");
    }

    @Test
    void testPrivateConstructor() throws IOException {
        String code = """
                package test;

                public class Utils {
                    private boolean check(String s) {
                        return s != null;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "check");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "check",
                List.of(),
                "boolean",
                "test.util",
                0.92,
                5);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String utilityCode = result.refactoredCode();

        // Should have private constructor with UnsupportedOperationException
        assertTrue(utilityCode.contains("private " + result.utilityClassName() + "()"),
                "Should have private constructor");
        assertTrue(utilityCode.contains("UnsupportedOperationException"),
                "Constructor should throw UnsupportedOperationException");
    }

    @Test
    void testStaticMethodConversion() throws IOException {
        String code = """
                package test;

                public class Service {
                    private String process(String input) {
                        return input.toUpperCase();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "process");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "process",
                List.of(),
                "String",
                "test.util",
                0.91,
                7);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String utilityCode = result.refactoredCode();

        // Method should be public static
        assertTrue(utilityCode.contains("public static"), "Method should be public static");
        assertFalse(utilityCode.contains("private String process"), "Should not be private");
    }

    @Test
    void testJavaDocGeneration() throws IOException {
        String code = """
                package test;

                public class Validator {
                    private boolean validateEmail(String email) {
                        return email != null && email.contains("@");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicateCluster cluster = createMockCluster(cu, "validateEmail");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS,
                "validateEmail",
                List.of(),
                "boolean",
                "test.util",
                0.93,
                8);

        ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String utilityCode = result.refactoredCode();

        // Should have JavaDoc for class
        assertTrue(utilityCode.contains("/**"), "Should have JavaDoc");
        assertTrue(utilityCode.contains("* Utility class"), "Should document as utility class");
    }

    private DuplicateCluster createMockCluster(CompilationUnit cu, String methodName) {
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                m -> m.getNameAsString().equals(methodName))
                .orElseThrow(() -> new IllegalStateException("Method not found: " + methodName));

        var statements = method.getBody().orElseThrow().getStatements();

        StatementSequence seq = new StatementSequence(
                statements.stream().toList(),
                new Range(1, statements.size(), 1, 1),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        return new DuplicateCluster(seq, List.of(), null, 5);
    }
}
