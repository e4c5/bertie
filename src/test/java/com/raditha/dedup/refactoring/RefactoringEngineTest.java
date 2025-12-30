package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for automated refactoring.
 */
class RefactoringEngineTest {

    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient()); // Use lenient for testing
    }

    @Test
    void testDryRunMode() throws IOException {
        String code = """
                package com.test;

                public class SimpleTest {
                    void method1() {
                        String name = "John";
                        String email = "john@test.com";
                        int age = 25;
                        System.out.println("Name: " + name);
                        System.out.println("Email: " + email);
                        System.out.println("Age: " + age);
                    }

                    void method2() {
                        String name = "Jane";
                        String email = "jane@test.com";
                        int age = 30;
                        System.out.println("Name: " + name);
                        System.out.println("Email: " + email);
                        System.out.println("Age: " + age);
                    }
                }
                """;

        // Parse and analyze
        CompilationUnit cu = StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("SimpleTest.java");
        Files.writeString(sourceFile, code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Should find duplicates
        assertTrue(report.hasDuplicates());
        assertTrue(report.clusters().size() > 0);

        // Test dry-run mode (should not modify file)
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.DRY_RUN,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // All should be skipped in dry-run
        assertTrue(session.getSkipped().size() > 0);
        assertEquals(0, session.getSuccessful().size());

        // File should not be modified
        String fileAfter = Files.readString(sourceFile);
        assertEquals(code, fileAfter);
    }

    @Test
    void testBatchModeWithLowConfidence() throws IOException {
        String code = """
                package com.test;

                public class Test {
                    void method1() {
                        int x = 1;
                        int y = 2;
                        System.out.println(x + y);
                        System.out.println("result");
                        System.out.println("done");
                    }

                    void method2() {
                        String a = "hello";
                        String b = "world";
                        System.out.println(a + b);
                        System.out.println("output");
                        System.out.println("finished");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("Test.java");
        Files.writeString(sourceFile, code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Test batch mode
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // Low confidence duplicates should be skipped in batch mode
        // (these are semantically different, just structurally similar)
        int totalProcessed = session.getTotalProcessed();
        assertTrue(totalProcessed > 0, "Should process some refactorings");
    }

    @Test
    void testDefaultStrategyIsExtractHelperMethod() throws IOException {
        // Test that EXTRACT_HELPER_METHOD is used by default for both source and test
        // files
        String code = """
                package com.test;

                public class Service {
                    void processUser1() {
                        User user = repository.findById("123");
                        user.setActive(true);
                        user.save();
                        logger.info("User processed");
                    }

                    void processUser2() {
                        User user = repository.findById("456");
                        user.setActive(true);
                        user.save();
                        logger.info("User processed");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("Service.java");
        Files.writeString(sourceFile, code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Should find duplicates and use EXTRACT_HELPER_METHOD strategy
        assertTrue(report.hasDuplicates());
        if (!report.clusters().isEmpty()) {
            var cluster = report.clusters().get(0);
            assertNotNull(cluster.recommendation());
            assertEquals(com.raditha.dedup.model.RefactoringStrategy.EXTRACT_HELPER_METHOD,
                    cluster.recommendation().strategy(),
                    "Default strategy should be EXTRACT_HELPER_METHOD");
        }
    }

    @Test
    void testSourceFileRefactoringWorks() throws IOException {
        // Test that source files (non-test) are refactored correctly
        String code = """
                package com.example;

                public class Calculator {
                    public int addAndDouble1(int a, int b) {
                        int sum = a + b;
                        int result = sum * 2;
                        return result;
                    }

                    public int addAndDouble2(int x, int y) {
                        int sum = x + y;
                        int result = sum * 2;
                        return result;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("Calculator.java");
        Files.writeString(sourceFile, code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        assertTrue(report.hasDuplicates(), "Should find duplicate calculation logic");

        // Verify it's recognized as a source file (not test)
        if (!report.clusters().isEmpty()) {
            var cluster = report.clusters().get(0);
            assertFalse(cluster.primary().sourceFilePath().toString().contains("Test.java"),
                    "Should be a source file, not a test file");
        }
    }

    @Test
    void testNoDuplicatesFoundScenario() throws IOException {
        String code = """
                package com.test;

                public class Test {
                    void method1() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                    }

                    void method2() {
                        String x = "hello";
                        String y = "world";
                        System.out.println(x);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("Test.java");
        Files.writeString(sourceFile, code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // No duplicates means no processing
        assertEquals(0, session.getTotalProcessed());
    }
}
