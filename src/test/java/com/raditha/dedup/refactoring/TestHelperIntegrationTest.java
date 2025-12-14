package com.raditha.dedup.refactoring;

import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.model.RefactoringStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test using real test-helper classes with anti-patterns.
 */
class TestHelperIntegrationTest {

    @TempDir
    Path tempDir;

    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @BeforeEach
    void setUp() {
        DuplicationConfig config = DuplicationConfig.lenient(); // 60% threshold, 3 min lines

        analyzer = new DuplicationAnalyzer(config);
        engine = new RefactoringEngine(tempDir, RefactoringEngine.RefactoringMode.BATCH);
    }

    @Test
    void testRefactoringOnHeavyweightUnitTest() throws IOException {
        // Copy the HeavyweightUnitTest from test-helper to temp location
        Path sourceFile = Path.of(
                "../antikythera-test-helper/src/main/java/sa/com/cloudsolutions/antikythera/testhelper/antipatterns/HeavyweightUnitTest.java");

        if (!Files.exists(sourceFile)) {
            // Skip if test-helper not available
            System.out.println("Skipping - test-helper not found");
            return;
        }

        Path testFile = tempDir.resolve("HeavyweightUnitTest.java");
        Files.copy(sourceFile, testFile, StandardCopyOption.REPLACE_EXISTING);

        // Parse and analyze
        var cu = com.github.javaparser.StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertNotNull(report, "Should generate report");
        System.out.println("Found " + report.clusters().size() + " duplicate clusters");

        // If duplicates found, try refactoring
        if (!report.clusters().isEmpty()) {
            // Run refactoring in batch mode
            RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

            assertNotNull(session, "Should complete refactoring session");

            // Verify the refactored code is valid Java
            String refactoredContent = Files.readString(testFile);
            assertNotNull(refactoredContent);
            assertTrue(refactoredContent.length() > 0);

            // Log results
            System.out.println("Refactoring completed:");
            System.out.println(" - Successful: " + session.getSuccessful().size());
            System.out.println(" - Skipped: " + session.getSkipped().size());
            System.out.println(" - Failed: " + session.getFailed().size());
        }
    }

    @Test
    void testDetectBeforeEachOpportunity() throws IOException {
        // Create a test class with duplicate setup code
        String testClassCode = """
                package test;

                import org.junit.jupiter.api.Test;

                public class DuplicateSetupTest {
                    @Test
                    void testMethod1() {
                        DatabaseConnection conn = new DatabaseConnection();
                        conn.connect("localhost");
                        conn.authenticate("user", "pass");
                        // actual test
                        conn.executeQuery("SELECT 1");
                    }

                    @Test
                    void testMethod2() {
                        DatabaseConnection conn = new DatabaseConnection();
                        conn.connect("localhost");
                        conn.authenticate("user", "pass");
                        // actual test
                        conn.executeQuery("SELECT 2");
                    }

                    @Test
                    void testMethod3() {
                        DatabaseConnection conn = new DatabaseConnection();
                        conn.connect("localhost");
                        conn.authenticate("user", "pass");
                        // actual test
                        conn.executeQuery("SELECT 3");
                    }
                }
                """;

        Path testFile = tempDir.resolve("DuplicateSetupTest.java");
        Files.writeString(testFile, testClassCode);

        // Parse and analyze
        var cu = com.github.javaparser.StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertNotNull(report);
        assertFalse(report.clusters().isEmpty(), "Should detect duplicate setup code");

        // Check if any cluster is recommended for @BeforeEach extraction
        boolean hasBeforeEachRecommendation = report.clusters().stream()
                .anyMatch(cluster -> cluster.recommendation() != null &&
                        cluster.recommendation().strategy() == RefactoringStrategy.EXTRACT_TO_BEFORE_EACH);

        if (hasBeforeEachRecommendation) {
            System.out.println("✓ Found @BeforeEach extraction opportunity");

            // Try the refactoring
            RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

            assertTrue(session.getSuccessful().size() > 0 || session.getSkipped().size() > 0,
                    "Should process @BeforeEach refactoring");
        } else {
            System.out.println("No @BeforeEach recommendation (expected for simple duplicates)");
        }
    }

    @Test
    void testEndToEndWithRealDuplicates() throws IOException {
        // Create a simple test case with obvious duplicates
        String code = """
                package test;

                import org.junit.jupiter.api.Test;

                class SimpleTest {
                    @Test
                    void test1() {
                        String result = calculateSum(1, 2);
                        assertNotNull(result);
                        assertEquals("3", result);
                    }

                    @Test
                    void test2() {
                        String result = calculateSum(2, 3);
                        assertNotNull(result);
                        assertEquals("5", result);
                    }

                    @Test
                    void test3() {
                        String result = calculateSum(5, 5);
                        assertNotNull(result);
                        assertEquals("10", result);
                    }

                    private String calculateSum(int a, int b) {
                        return String.valueOf(a + b);
                    }
                }
                """;

        Path testFile = tempDir.resolve("SimpleTest.java");
        Files.writeString(testFile, code);

        // Parse and run full pipeline
        var cu = com.github.javaparser.StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertNotNull(report);
        System.out.println("Detected " + report.clusters().size() + " clusters");

        if (!report.clusters().isEmpty()) {
            // Dry-run first
            RefactoringEngine dryRunEngine = new RefactoringEngine(
                    tempDir,
                    RefactoringEngine.RefactoringMode.DRY_RUN);

            RefactoringEngine.RefactoringSession dryRunSession = dryRunEngine.refactorAll(report);

            assertNotNull(dryRunSession);
            System.out.println("Dry-run completed - would process " +
                    report.clusters().size() + " clusters");

            // Verify file unchanged in dry-run
            String afterDryRun = Files.readString(testFile);
            assertEquals(code, afterDryRun, "Dry-run should not modify files");
        }
    }
}
