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
import java.util.ArrayList;
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

    @Test
    void testNoDuplicateCsvSourceEntries() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                class DuplicateTest {
                    @Test
                    void testA() { assertEquals(1, calc(1)); }

                    @Test
                    void testB() { assertEquals(2, calc(2)); }

                    @Test
                    void testC() { assertEquals(3, calc(3)); }

                    @Test
                    void testD() { assertEquals(4, calc(4)); }

                    @Test
                    void testE() { assertEquals(5, calc(5)); }

                    int calc(int x) { return x; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, 5);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                "testCalc",
                List.of(),
                null,
                "DuplicateTest",
                0.92,
                8);

        ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Extract the @CsvSource annotation content
        assertTrue(refactored.contains("@CsvSource"), "Should have @CsvSource annotation");

        // Parse the refactored code and extract CSV values
        CompilationUnit refactoredCu = StaticJavaParser.parse(refactored);
        var csvAnnotation = refactoredCu.findFirst(com.github.javaparser.ast.expr.NormalAnnotationExpr.class,
                a -> a.getNameAsString().equals("CsvSource"))
                .orElseThrow(() -> new AssertionError("@CsvSource annotation not found"));

        // Extract the value array from the annotation
        String csvContent = csvAnnotation.toString();
        
        // Count the number of CSV rows (each row is quoted)
        int rowCount = 0;
        for (int i = 0; i < csvContent.length() - 1; i++) {
            if (csvContent.charAt(i) == '"' && csvContent.charAt(i + 1) != '"') {
                rowCount++;
            }
        }
        // Divide by 2 because each row has opening and closing quotes
        rowCount = rowCount / 2;

        // Should have exactly 5 unique entries (one per test method), not more
        assertEquals(5, rowCount, 
            "Should have exactly 5 CSV entries (one per unique test method), not duplicates. " +
            "If this fails, the extractTestInstances method is creating duplicate TestInstance objects.");

        // Verify the CSV content doesn't have obvious duplicates
        assertFalse(csvContent.matches(".*\"(\\d+)\".*\"\\1\".*"), 
            "CSV should not contain duplicate rows with the same value");
    }

    /**
     * Create a mock cluster for testing.
     * 
     * <p>This creates a realistic cluster with cross-pairs between methods to properly
     * test duplicate detection. For N methods, it creates pairs between all combinations,
     * simulating what DuplicateClusterer would produce for a connected component.
     * 
     * <p>For example, with 3 methods (A, B, C), it creates:
     * <ul>
     *   <li>Primary: A</li>
     *   <li>Pair 1: A vs B</li>
     *   <li>Pair 2: A vs C</li>
     *   <li>Pair 3: B vs C</li>
     * </ul>
     * 
     * <p>This structure exposes the duplicate bug: if we naively iterate through pairs
     * and extract seq2(), method C would be added twice (from pairs 2 and 3).
     * 
     * <p><b>Important:</b> Each StatementSequence must have a unique range because
     * {@link StatementSequence#equals} only compares range, startOffset, and sourceFilePath
     * (not containingMethod). Without unique ranges, all sequences would be considered equal
     * and {@link DuplicateCluster#getContainingMethods()} would return only one method.
     */
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

        // Create StatementSequence for each method with UNIQUE ranges
        // This is critical because StatementSequence.equals() only compares range/offset,
        // not the containingMethod. Without unique ranges, all sequences would be equal.
        List<StatementSequence> sequences = new ArrayList<>();
        for (int i = 0; i < testMethods.size(); i++) {
            MethodDeclaration method = testMethods.get(i);
            var statements = method.getBody().orElseThrow().getStatements();
            
            // Give each sequence a unique range based on its index
            // This ensures StatementSequence.equals() treats them as distinct
            StatementSequence seq = new StatementSequence(
                    statements.stream().toList(),
                    new Range(i * 10 + 1, i * 10 + statements.size(), 1, 1), // Unique range per method
                    0,
                    method,
                    cu,
                    Paths.get("Test.java"));
            sequences.add(seq);
        }

        // Primary is the first sequence
        StatementSequence primary = sequences.get(0);

        // Create cross-pairs between all methods (simulating a connected component)
        // This creates pairs: (0,1), (0,2), (1,2), (0,3), (1,3), (2,3), etc.
        List<SimilarityPair> duplicates = new ArrayList<>();
        for (int i = 0; i < sequences.size(); i++) {
            for (int j = i + 1; j < sequences.size(); j++) {
                duplicates.add(new SimilarityPair(
                        sequences.get(i),
                        sequences.get(j),
                        new SimilarityResult(0.95, 0.95, 0.95, 0.95, 0, 0, null, null, true)));
            }
        }

        return new DuplicateCluster(primary, duplicates, null, testCount * 2);
    }
}
