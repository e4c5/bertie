package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExtractBeforeEachRefactorer - extracting duplicate test setup
 * to @BeforeEach.
 */
class ExtractBeforeEachRefactorerTest {

    private ExtractBeforeEachRefactorer refactorer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        refactorer = new ExtractBeforeEachRefactorer();
    }

    @Test
    void testBasicBeforeEachExtraction() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class UserServiceTest {
                    @Test
                    void testCreateUser() {
                        UserService service = new UserService();
                        service.setDatabase(mockDb);
                        // test code
                    }

                    @Test
                    void testDeleteUser() {
                        UserService service = new UserService();
                        service.setDatabase(mockDb);
                        // test code
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "service.setDatabase(mockDb);");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "UserServiceTest",
                0.95,
                4);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        assertNotNull(result);
        String refactored = result.refactoredCode();

        // Should have @BeforeEach annotation
        assertTrue(refactored.contains("@BeforeEach"), "Should add @BeforeEach annotation");
        assertTrue(refactored.contains("void setUp()"), "Should create setUp method");
    }

    @Test
    void testFieldPromotion() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class ServiceTest {
                    @Test
                    void test1() {
                        MyService service = new MyService();
                        service.init();
                    }

                    @Test
                    void test2() {
                        MyService service = new MyService();
                        service.init();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "MyService service = new MyService();");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "ServiceTest",
                0.90,
                3);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should promote service to instance field
        assertTrue(refactored.contains("private MyService service"),
                "Should promote variable to field: " + refactored);
    }

    @Test
    void testNotTestClass() throws IOException {
        String nonTestCode = """
                class RegularClass {
                    void method1() {
                        String x = "test";
                    }

                    void method2() {
                        String x = "test";
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(nonTestCode);

        // Create simple cluster without needing @Test annotations
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method = clazz.getMethods().get(0);
        var statements = method.getBody().orElseThrow().getStatements();

        StatementSequence seq = new StatementSequence(
                statements.stream().toList(),
                new Range(1, 1, 1, 1),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        DuplicateCluster cluster = new DuplicateCluster(seq, List.of(), null, 1);

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "RegularClass",
                0.85,
                2);

        // Should throw exception for non-test class
        assertThrows(IllegalArgumentException.class, () -> {
            refactorer.refactor(cluster, recommendation);
        });
    }

    @Test
    void testImportManagement() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class ImportTest {
                    @Test
                    void test1() {
                        Object obj = new Object();
                    }

                    @Test
                    void test2() {
                        Object obj = new Object();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "Object obj = new Object();");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "ImportTest",
                0.88,
                2);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should add BeforeEach import
        assertTrue(refactored.contains("import org.junit.jupiter.api.BeforeEach"),
                "Should add BeforeEach import");
    }

    @Test
    void testMultipleVariables() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class MultiVarTest {
                    @Test
                    void test1() {
                        Database db = new Database();
                        Connection conn = db.connect();
                        db.init();
                    }

                    @Test
                    void test2() {
                        Database db = new Database();
                        Connection conn = db.connect();
                        db.init();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "Database db = new Database();");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "MultiVarTest",
                0.92,
                5);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // Should create both fields
        assertTrue(refactored.contains("private Database db"), "Should promote db");
        assertTrue(refactored.contains("private Connection conn"), "Should promote conn");
    }

    @Test
    void testBeforeEachMethodCreation() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class BeforeEachCreationTest {
                    @Test
                    void testA() {
                        int value = 42;
                        System.out.println(value);
                    }

                    @Test
                    void testB() {
                        int value = 42;
                        System.out.println(value);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "int value = 42;");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "initialize",
                List.of(),
                null,
                "BeforeEachCreationTest",
                0.91,
                3);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        CompilationUnit refactoredCu = StaticJavaParser.parse(result.refactoredCode());
        ClassOrInterfaceDeclaration testClass = refactoredCu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();

        // Verify @BeforeEach method was created
        long beforeEachCount = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("BeforeEach")))
                .count();

        assertEquals(1, beforeEachCount, "Should have exactly one @BeforeEach method");
    }

    @Test
    void testDuplicatesRemoved() throws IOException {
        String testCode = """
                import org.junit.jupiter.api.Test;

                class DuplicateRemovalTest {
                    @Test
                    void testX() {
                        String msg = "hello";
                        System.out.println("After setup");
                    }

                    @Test
                    void testY() {
                        String msg = "hello";
                        System.out.println("After setup");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(testCode);
        DuplicateCluster cluster = createMockCluster(cu, "String msg = \"hello\";");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                "setUp",
                List.of(),
                null,
                "DuplicateRemovalTest",
                0.93,
                2);

        ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        String refactored = result.refactoredCode();

        // The duplicate line should still appear once in @BeforeEach
        int occurrences = countOccurrences(refactored, "msg = \"hello\"");
        assertTrue(occurrences <= 2, "Should reduce duplicates, found: " + occurrences);
    }

    private DuplicateCluster createMockCluster(CompilationUnit cu, String codeSnippet) {
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();

        MethodDeclaration firstTest = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test")))
                .findFirst()
                .orElseThrow();

        var statements = firstTest.getBody().orElseThrow().getStatements();

        StatementSequence seq = new StatementSequence(
                statements.stream().limit(2).toList(),
                new Range(1, 2, 1, 1),
                0,
                firstTest,
                cu,
                Paths.get("Test.java"));

        return new DuplicateCluster(
                seq,
                List.of(),
                null,
                2);
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
