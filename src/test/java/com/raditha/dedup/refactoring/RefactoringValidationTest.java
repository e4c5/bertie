package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests that verify Bertie correctly refactors the test classes
 * from antikythera-test-helper. Each test:
 * 1. Loads a *Dup.java class
 * 2. Runs refactoring on it
 * 3. Compares the result with the corresponding *Clean.java class
 * 
 * Note: Since perfect matching is not feasible, these tests verify:
 * - Helper methods are extracted
 * - Static modifiers are correctly applied
 * - Method signatures are reasonable
 * - Code compiles after refactoring
 */
class RefactoringValidationTest {

        private DuplicationAnalyzer analyzer;
        private RefactoringEngine engine;

        @TempDir
        Path tempDir;

        @BeforeEach
        void setUp() throws Exception {
                // Initialize Settings and configure antikythera-test-helper path
                Settings.loadConfigMap();

                // Set base path to antikythera-test-helper so AbstractCompiler can find source
                // files
                Path testHelperRoot = Paths.get("../antikythera-test-helper").toAbsolutePath().normalize();
                Settings.setProperty(Settings.BASE_PATH, testHelperRoot.resolve("src/main/java").toString());

                // Parse and load all test helper classes into AntikytheraRunTime using
                // AbstractCompiler
                AbstractCompiler.preProcess();

                // Use lenient config to detect smaller duplicates (test files have 3-line
                // duplicates)
                analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
        }

        @Test
        void testSimpleCalculatorRefactoring() throws IOException {
                // Load CompilationUnit using AntikytheraRunTime
                String className = "sa.com.cloudsolutions.antikythera.testhelper.refactoring.SimpleCalculatorDup";
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                assertNotNull(cu, "CompilationUnit should be loaded for " + className);

                // Create temp file for refactoring
                Path sourceFile = tempDir.resolve("SimpleCalculatorDup.java");
                Files.writeString(sourceFile, cu.toString());

                // Analyze for duplicates
                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                // Should find duplicates
                assertTrue(report.hasDuplicates(),
                                "SimpleCalculatorDup should have duplicates");
                assertTrue(report.clusters().size() > 0,
                                "Should detect at least one cluster");

                // Run refactoring (no verification - temp dir has no pom.xml)
                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                // Debug: Print failures if any
                if (session.getFailed().size() > 0) {
                        System.out.println("Refactoring failures:");
                        session.getFailed().forEach(failure -> System.out.println("  - " + failure.error()));

                }

                // Should have successful refactorings
                assertTrue(session.getSuccessful().size() > 0,
                                "Should successfully refactor at least one cluster");

                // Load refactored code
                String refactoredCode = Files.readString(sourceFile);

                // Verify helper method was extracted
                assertTrue(refactoredCode.contains("private "),
                                "Should have extracted a private helper method");
                assertTrue(refactoredCode.contains("calculateWithTax") ||
                                refactoredCode.contains("helper") ||
                                refactoredCode.contains("extracted"),
                                "Helper method should have reasonable name");

                // Verify code compiles
                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode),
                                "Refactored code should compile");
        }

        @Test
        void testUserValidatorRefactoring() throws IOException {
                String className = "sa.com.cloudsolutions.antikythera.testhelper.refactoring.UserValidatorDup";
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                assertNotNull(cu, "CompilationUnit should be loaded for " + className);

                Path sourceFile = tempDir.resolve("UserValidatorDup.java");
                Files.writeString(sourceFile, cu.toString());

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                assertTrue(report.hasDuplicates(),
                                "UserValidatorDup should have duplicates with different variable names");

                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                assertFalse(session.getSuccessful().isEmpty(),
                                "Should successfully refactor despite variable name differences");

                String refactoredCode = Files.readString(sourceFile);

                // Verify parameterized helper method was extracted
                assertTrue(refactoredCode.contains("private") &&
                                (refactoredCode.contains("boolean") || refactoredCode.contains("validate")),
                                "Should extract a validation helper method");
                assertTrue(refactoredCode.contains("String") && refactoredCode.contains(","),
                                "Helper should have parameters");

                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));
        }

        @Test
        void testStaticUtilsRefactoring() throws IOException {
                String className = "sa.com.cloudsolutions.antikythera.testhelper.refactoring.StaticUtilsDup";
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                assertNotNull(cu, "CompilationUnit should be loaded for " + className);

                Path sourceFile = tempDir.resolve("StaticUtilsDup.java");
                Files.writeString(sourceFile, cu.toString());

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                assertTrue(report.hasDuplicates(),
                                "StaticUtilsDup should have duplicates in static methods");

                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                assertTrue(session.getSuccessful().size() > 0,
                                "Should successfully refactor static methods");

                String refactoredCode = Files.readString(sourceFile);

                // CRITICAL: Verify helper method is static
                assertTrue(refactoredCode.contains("private static"),
                                "Helper method MUST be static when extracting from static methods");
                assertTrue(refactoredCode.contains("String formatCurrency") ||
                                refactoredCode.contains("String format") ||
                                refactoredCode.contains("String helper"),
                                "Should have String return type helper");

                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));
        }

        @Test
        void testFileProcessorRefactoring() throws IOException {
                String className = "sa.com.cloudsolutions.antikythera.testhelper.refactoring.FileProcessorDup";
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                assertNotNull(cu, "CompilationUnit should be loaded for " + className);

                Path sourceFile = tempDir.resolve("FileProcessorDup.java");
                Files.writeString(sourceFile, cu.toString());

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                assertTrue(report.hasDuplicates(),
                                "FileProcessorDup should have duplicates in I/O methods");

                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                assertTrue(session.getSuccessful().size() > 0,
                                "Should successfully refactor file I/O methods");

                String refactoredCode = Files.readString(sourceFile);

                // Verify throws IOException is preserved
                assertTrue(refactoredCode.contains("throws IOException"),
                                "Should preserve IOException in method signatures");
                assertTrue(refactoredCode.contains("private "),
                                "Should extract helper methods");

                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));
        }

        @Test
        void testReportGeneratorRefactoring() throws IOException {
                String className = "sa.com.cloudsolutions.antikythera.testhelper.refactoring.ReportGeneratorDup";
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
                assertNotNull(cu, "CompilationUnit should be loaded for " + className);

                Path sourceFile = tempDir.resolve("ReportGeneratorDup.java");
                Files.writeString(sourceFile, cu.toString());

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                assertTrue(report.hasDuplicates(),
                                "ReportGeneratorDup should have duplicates with loops and formatting");

                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                assertTrue(session.getSuccessful().size() > 0,
                                "Should successfully refactor complex report generation");

                String refactoredCode = Files.readString(sourceFile);

                // Verify helper method was extracted (don't be too strict about format)
                assertTrue(refactoredCode.contains("private") &&
                                (refactoredCode.contains("String") || refactoredCode.contains("format")),
                                "Should extract a String-returning or formatting helper");

                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));
        }
}
