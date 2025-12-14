package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExtractParameterizedTestRefactorer - converting duplicate tests
 * to @ParameterizedTest.
 */
class ExtractParameterizedTestRefactorerTest {

    private ExtractParameterizedTestRefactorer refactorer;

    @BeforeEach
    void setUp() {
        refactorer = new ExtractParameterizedTestRefactorer();
    }

    @Test
    void testBasicParameterization() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                class CalculatorTest {
                    @Test
                    void testAdd_2_plus_3() {
                        assertEquals(5, add(2, 3));
                    }

                    @Test
                    void testAdd_5_plus_7() {
                        assertEquals(12, add(5, 7));
                    }

                    @Test
                    void testAdd_10_plus_15() {
                        assertEquals(25, add(10, 15));
                    }

                    int add(int a, int b) { return a + b; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testAdd",
                List.of(),
                null,
                "CalculatorTest",
                0.95,
                6);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        assertNotNull(result);
        String refactored = result.refactoredCode();

        // Should have @ParameterizedTest annotation
        assertTrue(refactored.contains("@ParameterizedTest"), "Should add @ParameterizedTest");
        assertTrue(refactored.contains("@CsvSource"), "Should add @CsvSource");
        assertTrue(refactored.contains("void testAdd("), "Should create parameterized method");
    }

    @Test
    void testImportManagement() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class ImportTest {
                    @Test
                    void test1() { assertEquals(1, calc(1)); }

                    @Test
                    void test2() { assertEquals(2, calc(2)); }

                    @Test
                    void test3() { assertEquals(3, calc(3)); }

                    int calc(int x) { return x; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testCalc",
                List.of(),
                null,
                "ImportTest",
                0.90,
                4);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should add parameterized test imports
        assertTrue(refactored.contains("import org.junit.jupiter.params.ParameterizedTest"),
                "Should add ParameterizedTest import");
        assertTrue(refactored.contains("import org.junit.jupiter.params.provider.CsvSource"),
                "Should add CsvSource import");
    }

    @Test
    void testNotEnoughTests() {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class TooFewTest {
                    @Test
                    void test1() { assertEquals(1, 1); }

                    @Test
                    void test2() { assertEquals(2, 2); }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 2);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "test",
                List.of(),
                null,
                "TooFewTest",
                0.85,
                2);

        // Should throw exception for < 3 tests
        assertThrows(IllegalArgumentException.class, () -> {
            refactorer.refactor(cluster, recommendation);
        });
    }

    @Test
    void testNotTestClass() {
        String nonTestCode = """
                class RegularClass {
                    void method1() { doSomething(1); }
                    void method2() { doSomething(2); }
                    void method3() { doSomething(3); }
                    void doSomething(int x) {}
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(nonTestCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "method",
                List.of(),
                null,
                "RegularClass",
                0.85,
                3);

        // Should throw exception for non-test class
        assertThrows(IllegalArgumentException.class, () -> {
            refactorer.refactor(cluster, recommendation);
        });
    }

    @Test
    void testMultipleParameters() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                class MultiParamTest {
                    @Test
                    void testDivide_10_by_2() {
                        assertEquals(5, divide(10, 2));
                    }

                    @Test
                    void testDivide_20_by_4() {
                        assertEquals(5, divide(20, 4));
                    }

                    @Test
                    void testDivide_100_by_10() {
                        assertEquals(10, divide(100, 10));
                    }

                    int divide(int a, int b) { return a / b; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testDivide",
                List.of(),
                null,
                "MultiParamTest",
                0.92,
                6);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should create parameterized test with multiple parameters
        assertTrue(refactored.contains("@ParameterizedTest"));
        assertTrue(refactored.contains("@CsvSource"));
    }

    @Test
    void testStringParameters() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                class StringTest {
                    @Test
                    void testUpper_hello() {
                        assertEquals("HELLO", toUpper("hello"));
                    }

                    @Test
                    void testUpper_world() {
                        assertEquals("WORLD", toUpper("world"));
                    }

                    @Test
                    void testUpper_test() {
                        assertEquals("TEST", toUpper("test"));
                    }

                    String toUpper(String s) { return s.toUpperCase(); }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testUpper",
                List.of(),
                null,
                "StringTest",
                0.93,
                6);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should handle string literals
        assertTrue(refactored.contains("@ParameterizedTest"));
        assertTrue(refactored.contains("@CsvSource"));
    }

    @Test
    void testOriginalMethodsRemoved() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class RemovalTest {
                    @Test
                    void testA() { check(1); }

                    @Test
                    void testB() { check(2); }

                    @Test
                    void testC() { check(3); }

                    void check(int x) {}
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 3);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testCheck",
                List.of(),
                null,
                "RemovalTest",
                0.91,
                4);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        CompilationUnit refactoredCu = StaticJavaParser.parse(result.refactoredCode());
        ClassOrInterfaceDeclaration testClass = refactoredCu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();

        // Count @Test methods (should be 0 after refactoring)
        long testMethods = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test")))
                .count();

        assertEquals(0, testMethods, "Original @Test methods should be removed");

        // Should have 1 @ParameterizedTest method
        long parameterizedTests = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("ParameterizedTest")))
                .count();

        assertEquals(1, parameterizedTests, "Should have one @ParameterizedTest method");
    }

    private DuplicateCluster createMockCluster(CompilationUnit cu, int testCount) {
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();

        List<MethodDeclaration> testMethods = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test")))
                .limit(testCount)
                .toList();

        if (testMethods.isEmpty()) {
            testMethods = testClass.getMethods().stream().limit(testCount).toList();
        }

        MethodDeclaration firstTest = testMethods.get(0);
        var statements = firstTest.getBody().orElseThrow().getStatements();

        StatementSequence seq = new StatementSequence(
                statements.stream().toList(),
                new Range(1, statements.size(), 1, 1),
                0,
                firstTest,
                cu,
                Paths.get("Test.java"));

        // Create similarity pairs for remaining methods
        List<SimilarityPair> duplicates = testMethods.stream()
                .skip(1)
                .map(method -> {
                    var stmts = method.getBody().orElseThrow().getStatements();
                    StatementSequence seq2 = new StatementSequence(
                            stmts.stream().toList(),
                            new Range(1, stmts.size(), 1, 1),
                            0,
                            method,
                            cu,
                            Paths.get("Test.java"));
                    return new SimilarityPair(seq, seq2,
                            new SimilarityResult(0.95, 0.95, 0.95, 0.95, 0, 0, null, null, true));
                })
                .toList();

        return new DuplicateCluster(seq, duplicates, null, testCount * 2);
    }
}
