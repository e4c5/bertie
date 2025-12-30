package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.analysis.EscapeAnalyzer.EscapeAnalysis;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EscapeAnalyzer.
 * Uses Antikythera's AbstractCompiler with real test-bed sources.
 */
class EscapeAnalyzerTest {

    private static EscapeAnalyzer analyzer;

    @BeforeAll
    static void setUp() throws IOException {
        // Load test configuration
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        analyzer = new EscapeAnalyzer();
    }

    @Test
    void testAnalyze_WithTestBedCode() {
        // Use test-bed code that has variable usage
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest");

        assertNotNull(cu, "Test-bed class should be parsed");

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);
        if (method != null && method.getBody().isPresent()) {
            List<Statement> stmts = method.getBody().get().getStatements();
            if (!stmts.isEmpty()) {
                StatementSequence sequence = new StatementSequence(
                        stmts,
                        new com.raditha.dedup.model.Range(1, stmts.size(), 1, 10),
                        0,
                        method,
                        cu,
                        Paths.get("Test.java"));

                // Test analyze method
                EscapeAnalysis result = analyzer.analyze(sequence);
                assertNotNull(result, "Analysis should return a result");
            }
        }
    }

    @Test
    void testAnalyze_ChecksCapturedVariables() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest");

        if (cu != null) {
            MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);
            if (method != null && method.getBody().isPresent()) {
                List<Statement> stmts = method.getBody().get().getStatements();
                if (stmts.size() >= 2) {
                    StatementSequence sequence = new StatementSequence(
                            stmts.subList(0, 2),
                            new com.raditha.dedup.model.Range(1, 2, 1, 10),
                            0,
                            method,
                            cu,
                            Paths.get("Test.java"));

                    // Test captured variables detection
                    EscapeAnalysis result = analyzer.analyze(sequence);
                    assertNotNull(result, "Should return analysis result");
                    assertNotNull(result.capturedVariables(), "Should have captured variables set");
                }
            }
        }
    }

    @Test
    void testAnalyze_ChecksEscapingReads() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest");

        if (cu != null) {
            MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);
            if (method != null && method.getBody().isPresent()) {
                List<Statement> stmts = method.getBody().get().getStatements();
                if (!stmts.isEmpty()) {
                    StatementSequence sequence = new StatementSequence(
                            List.of(stmts.get(0)),
                            new com.raditha.dedup.model.Range(1, 1, 1, 10),
                            0,
                            method,
                            cu,
                            Paths.get("Test.java"));

                    // Test escaping reads
                    EscapeAnalysis result = analyzer.analyze(sequence);
                    assertNotNull(result, "Should return analysis result");
                    assertNotNull(result.escapingReads(), "Should have escaping reads set");
                }
            }
        }
    }

    @Test
    void testAnalyzerInitialization() {
        assertNotNull(analyzer, "Analyzer should be initialized");
    }

    @Test
    void testTestBedVariableCaptureClassParsed() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest");
        assertNotNull(cu, "Variable capture test class should be parsed");
    }

    @Test
    void testEscapeAnalysisResultStructure() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest");

        if (cu != null) {
            MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);
            if (method != null && method.getBody().isPresent()) {
                List<Statement> stmts = method.getBody().get().getStatements();
                if (!stmts.isEmpty()) {
                    StatementSequence sequence = new StatementSequence(
                            stmts,
                            new com.raditha.dedup.model.Range(1, stmts.size(), 1, 10),
                            0,
                            method,
                            cu,
                            Paths.get("Test.java"));

                    EscapeAnalysis result = analyzer.analyze(sequence);

                    // Verify all fields exist
                    assertNotNull(result.capturedVariables(), "Should have captured variables");
                    assertNotNull(result.escapingWrites(), "Should have escaping writes");
                    assertNotNull(result.escapingReads(), "Should have escaping reads");
                    assertNotNull(result.localVariables(), "Should have local variables");
                }
            }
        }
    }
}
