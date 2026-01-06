package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractMethodRefactorerTest {
    @TempDir
    Path tempDir;
    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

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
        // Use lenient config to ensure small code blocks are detected
        // Pass empty map to avoid global scanning and duplicate class errors
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient(), Collections.emptyMap());
    }

    @Test
    void testMultipleSameTypeParameters() throws IOException, InterruptedException {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.wrongarguments.MultipleStringParams");
        Path sourceFile = tempDir.resolve("SimpleTest.java");
        Files.writeString(sourceFile, cu.toString());

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates");
        assertEquals(1, report.clusters().size(), "Should have 1 cluster");

        // Verify parameters
        var recommendation = report.clusters().get(0).recommendation();
        System.out.println("DEBUG: Recommendations:");
        recommendation.getSuggestedParameters().forEach(p -> System.out
                .println("Param: " + p.getName() + " Type: " + p.getType() + " Examples: "
                        + p.getExampleValues()));

        assertTrue(recommendation.getSuggestedParameters().size() >= 3,
                "Should have at least name, email, age parameters");

        // Use INTERACTIVE mode to force application regardless of confidence
        // Simulate "y" input for confirmation
        System.setIn(new java.io.ByteArrayInputStream("y\\n".getBytes()));

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.INTERACTIVE,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        if (session.getSuccessful().isEmpty()) {
            System.out.println("Skipped reasons:");
            session.getSkipped().forEach(s -> System.out.println("- " + s.reason()));
            session.getFailed().forEach(f -> System.out.println("- FAIL: " + f.error()));
        }

        assertEquals(1, session.getSuccessful().size());

        String refactoredCode = Files.readString(sourceFile);

        // Diagnostic output
        System.out.println("Refactored Code:\\n" + refactoredCode);

        // ASSERTION FOR THE BUG:
        assertTrue(refactoredCode.contains("\"bob@example.com\""),
                "Refactored code should preserve the specific email literal for Bob");

        assertTrue(refactoredCode.contains("\"alice@example.com\""),
                "Refactored code should preserve the specific email literal for Alice");

        assertTrue(refactoredCode.contains("processAlice()"), "Should preserve processAlice");
        assertTrue(refactoredCode.contains("processBob()"), "Should preserve processBob");

        String methodName = recommendation.getSuggestedMethodName();
        assertTrue(refactoredCode.contains(methodName + "("), "Should call the extracted method");
    }
}
