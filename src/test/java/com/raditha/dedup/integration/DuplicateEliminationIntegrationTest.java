package com.raditha.dedup.integration;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test to verify that the refactoring engine correctly eliminates
 * duplicates
 * without creating redundant helper methods.
 */
class DuplicateEliminationIntegrationTest {
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

        @Test
        void testRefactoringAvoidsDuplicateMethods() throws IOException, InterruptedException {
                Map<String, CompilationUnit> cus = AntikytheraRunTime.getResolvedCompilationUnits();

                String className = "com.raditha.bertie.testbed.statementremoval.ServiceWithDuplicatesAtDifferentPositions";
                CompilationUnit cu = cus.get(className);

                Path sourceFile = tempDir.resolve("SimpleTest.java");
                Files.writeString(sourceFile, cu.toString());

                // 2. Configure Analyzer
                com.raditha.dedup.config.DuplicationDetectorSettings.loadConfig(3, 70, null);
                DuplicationAnalyzer analyzer = new DuplicationAnalyzer(cus);

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);
                assertTrue(report.hasDuplicates(), "Should have found duplicates");

                // 4. Refactor
                RefactoringEngine engine = new RefactoringEngine(
                                Paths.get(Settings.getBasePath()),
                                RefactoringEngine.RefactoringMode.BATCH,
                                RefactoringVerifier.VerificationLevel.NONE // Skip compilation for unit test speed
                );

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                // 5. Verify Results
                CompilationUnit refactoredCU = session.getSuccessful().get(0).cluster().primary().compilationUnit();

                // Count private helper methods that return User
                List<MethodDeclaration> helperMethods = refactoredCU.findAll(MethodDeclaration.class).stream()
                                .filter(m -> m.getModifiers().stream()
                                                .anyMatch(mod -> mod.getKeyword().name().equals("PRIVATE")))
                                .filter(m -> m.getType().asString().equals("User"))
                                .filter(m -> m.getBody().isPresent()
                                                && m.getBody().get().toString().contains("new User()"))
                                .collect(Collectors.toList());

                // We expect exactly ONE such helper method to handle all duplicates
                assertEquals(1, helperMethods.size(),
                                "Should have exactly one extracted helper method, but found: " +
                                                helperMethods.stream().map(MethodDeclaration::getNameAsString)
                                                                .collect(Collectors.joining(", ")));

                // Check for duplicate signatures explicitly
                Map<String, Integer> signatureCounts = new HashMap<>();
                for (MethodDeclaration m : helperMethods) {
                        // Normalize signature (param types only)
                        String sig = m.getParameters().stream().map(p -> p.getType().asString())
                                        .collect(Collectors.joining(","));
                        signatureCounts.merge(sig, 1, Integer::sum);
                }

                for (Map.Entry<String, Integer> entry : signatureCounts.entrySet()) {
                        assertEquals(1, entry.getValue(),
                                        "Found " + entry.getValue() + " methods with signature types: "
                                                        + entry.getKey());
                }
        }
}
