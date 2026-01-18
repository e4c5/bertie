package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for automated refactoring.
 */
class RefactoringEngineTest {

    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @TempDir
    Path tempDir;

    @BeforeAll()
    static void setupClass() throws IOException {
        // Load test configuration pointing to test-bed
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse test sources
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(); // Use lenient for testing
    }

    @Test
    void testPrinting() throws IOException, InterruptedException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.simple.Printing");
        Path sourceFile = tempDir.resolve("SimpleTest.java");
        Files.writeString(sourceFile, cu.toString());

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Should find duplicates
        assertTrue(report.hasDuplicates());
        assertFalse(report.clusters().isEmpty());

        // Store original content
        String originalContent = cu.toString();

        // Test dry-run mode (should not modify file)
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.DRY_RUN,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // All should be skipped in dry-run
        assertFalse(session.getSkipped().isEmpty());
        assertEquals(0, session.getSuccessful().size());

        // File should not be modified in dry-run mode
        String fileAfter = Files.readString(sourceFile);
        assertEquals(originalContent, fileAfter, "Dry-run should not modify the file");
    }

    @Test
    void testBatchModeWithLowConfidence() throws IOException, InterruptedException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.simple.Addition");
        Path sourceFile = tempDir.resolve("Test.java");
        Files.writeString(sourceFile, cu.toString());

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
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.simple.Service");
        Path sourceFile = tempDir.resolve("Service.java");
        Files.writeString(sourceFile, cu.toString());

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Should find duplicates and use EXTRACT_HELPER_METHOD strategy
        assertTrue(report.hasDuplicates());
        if (!report.clusters().isEmpty()) {
            var cluster = report.clusters().get(0);
            assertNotNull(cluster.recommendation());
            assertEquals(com.raditha.dedup.model.RefactoringStrategy.EXTRACT_HELPER_METHOD,
                    cluster.recommendation().getStrategy(),
                    "Default strategy should be EXTRACT_HELPER_METHOD");
        }
    }

    @Test
    void testSourceFileRefactoringWorks() throws IOException {

        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.simple.Calculator");
        Path sourceFile = tempDir.resolve("Calculator.java");
        Files.writeString(sourceFile, cu.toString());

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
    void testNoDuplicatesFoundScenario() throws IOException, InterruptedException {
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
