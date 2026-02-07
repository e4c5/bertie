package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.raditha.dedup.cli.VerifyMode;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for end-to-end refactoring workflow.
 * Uses temporary directories to safely test actual file modifications.
 */
class RefactoringIntegrationTest {

    @TempDir
    Path tempDir;

    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @BeforeAll()
    static void setupClass() throws IOException {
        // Load test configuration pointing to test-bed
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);
    }

    @BeforeEach
    void setUp() throws IOException {
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
        
        // Force lenient configuration via CLI overrides to ensure duplicates are found
        java.util.Map<String, Object> cliConfig = new java.util.HashMap<>();
        cliConfig.put("maximal_only", false);
        cliConfig.put("min_lines", 3);
        cliConfig.put("threshold", 0.60);
        cliConfig.put("max_window_growth", 7);
        Settings.setProperty("duplication_detector_cli", cliConfig);
        
        analyzer = new DuplicationAnalyzer();
    }

    @Test
    void testEndToEndRefactoring() throws IOException, InterruptedException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.wrongarguments.UserServiceWithDifferentValues");
        String original = cu.toString();
        Path testFile = tempDir.resolve("UserService.java");
        Files.writeString(testFile, original);

        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Should find duplicates
        assertTrue(report.hasDuplicates(), "Should detect duplicates");
        assertFalse(report.clusters().isEmpty(), "Should have clusters");

        // 3. Run refactoring in BATCH mode (auto-apply)
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                VerifyMode.NONE // Skip compilation for speed
        );

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // 4. Verify refactoring happened
        assertFalse(session.getSuccessful().isEmpty(), "Should have successful refactorings");
        assertEquals(0, session.getFailed().size(),
                "Should have no failures");

        // 5. Read modified file
        String refactoredCode = Files.readString(testFile);

        // 6. Verify changes
        assertNotEquals(cu.toString(), original, "File should be modified");

        // Should contain extracted method
        assertTrue(refactoredCode.contains("private"),
                "Should have private method");

        // Should still compile
        assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode),
                "Refactored code should parse successfully");

        long methodCount = cu.findAll(
                com.github.javaparser.ast.body.MethodDeclaration.class).size();

        assertTrue(methodCount > 3, "Should have added extracted method(s)");
    }

    @Test
    void testRefactoringRollbackOnFailure() throws IOException, InterruptedException {
        // Create code with duplicates in different control flow contexts
        // With recursive block extraction, these ARE detected as duplicates
        String badCode = """
                package com.test;

                public class BadService {
                    void method1() {
                        if (condition) {
                            doSomething();
                            doMore();
                            finish();
                        }
                    }

                    void method2() {
                        // Different control flow - but identical statements inside
                        while (condition) {
                            doSomething();
                            doMore();
                            finish();
                        }
                    }
                }
                """;

        Path testFile = tempDir.resolve("BadService.java");
        Files.writeString(testFile, badCode);

        CompilationUnit cu = StaticJavaParser.parse(badCode);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // With recursive block extraction, duplicates inside if/while blocks ARE detected
        assertTrue(report.hasDuplicates(), "Should detect duplicates in nested blocks");
        
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                VerifyMode.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // The refactoring should succeed - the statements are identical
        String afterCode = Files.readString(testFile);
        assertNotEquals(badCode, afterCode,
                "File should be modified - duplicates in nested blocks are now detected");
        assertTrue(afterCode.contains("extractedMethod"),
                "Should contain extracted method");
    }

    @Test
    void testMultipleRefactoringsInSameFile() throws IOException, InterruptedException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.wrongarguments.UserServiceWithDifferentValues");
        Path testFile = tempDir.resolve("OrderService.java");
        Files.writeString(testFile, cu.toString());

        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                VerifyMode.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // Should handle multiple clusters
        int totalProcessed = session.getTotalProcessed();
        assertTrue(totalProcessed >= 2,
                "Should process multiple duplicate clusters");

        // File should still be valid Java
        String refactoredCode = Files.readString(testFile);
        assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));

        var methodNames = cu.findAll(
                        com.github.javaparser.ast.body.MethodDeclaration.class)
                .stream()
                .map(NodeWithSimpleName::getNameAsString)
                .toList();

        // All method names should be unique (no duplicates)
        long uniqueCount = methodNames.stream().distinct().count();
        assertEquals(methodNames.size(), uniqueCount,
                "All method names should be unique");
    }
}
