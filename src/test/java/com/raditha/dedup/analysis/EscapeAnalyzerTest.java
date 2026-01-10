package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
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

    public static final String WITH_COUNTER_VARIABLE = "com.raditha.bertie.testbed.variablecapture.ServiceWithCounterVariableTest";
    private static EscapeAnalyzer analyzer;

    @BeforeAll
    static void setUp() throws IOException {
        // Load test configuration
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();

        analyzer = new EscapeAnalyzer();
    }

    @Test
    void testAnalyze_WithTestBedCode() {
        // Use test-bed code that has variable usage
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                WITH_COUNTER_VARIABLE);

        assertNotNull(cu, "Test-bed class should be parsed");

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        List<Statement> stmts = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                stmts,
                new com.raditha.dedup.model.Range(1, stmts.size(), 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        // Test analyze method
        assertTrue(analyzer.analyze(sequence));
    }

    @Test
    void testAnalyze_ChecksCapturedVariables() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                WITH_COUNTER_VARIABLE);


        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                    methodDeclaration -> methodDeclaration.getNameAsString().equals("testProcessItemsAndCount_countsActiveUsers")
                ).orElseThrow();
        List<Statement> stmts = method.getBody().get().getStatements();
        StatementSequence sequence = new StatementSequence(
                stmts.subList(0, 2),
                new com.raditha.dedup.model.Range(1, 2, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        assertFalse(analyzer.analyze(sequence));
    }

    @Test
    void testAnalyze_ChecksEscapingReads() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                WITH_COUNTER_VARIABLE);

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        List<Statement> stmts = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                List.of(stmts.get(0)),
                new com.raditha.dedup.model.Range(1, 1, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        assertTrue(analyzer.analyze(sequence));
    }

    @Test
    void testAnalyzerInitialization() {
        assertNotNull(analyzer, "Analyzer should be initialized");
    }

    @Test
    void testTestBedVariableCaptureClassParsed() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                WITH_COUNTER_VARIABLE);
        assertNotNull(cu, "Variable capture test class should be parsed");
    }

    @Test
    void testEscapeAnalysisResultStructure() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                WITH_COUNTER_VARIABLE);

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();

        List<Statement> stmts = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                stmts,
                new com.raditha.dedup.model.Range(1, stmts.size(), 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        assertTrue(analyzer.analyze(sequence));
    }
}
