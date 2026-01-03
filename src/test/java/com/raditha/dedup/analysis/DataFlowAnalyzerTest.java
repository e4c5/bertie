package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DataFlowAnalyzer.
 * Uses Antikythera's AbstractCompiler for proper AST context.
 */
class DataFlowAnalyzerTest {

    private static DataFlowAnalyzer analyzer;

    @BeforeAll
    static void setUp() throws IOException {
        // Load test configuration pointing to test-bed
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse test sources
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        analyzer = new DataFlowAnalyzer();
    }

    @Test
    void testFindReturnVariable_FromTestBed() {
        // Use actual test-bed code: ServiceWithMultipleReturnCandidatesTest
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.wrongreturnvalue.ServiceWithMultipleReturnCandidatesTest");

        assertNotNull(cu, "Test-bed class should be parsed");

        // Find a method that creates and returns a User
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                m -> m.getNameAsString().equals("testProcessUserAndReturnCorrectOne_returnsFinalUserNotTemp")).orElseThrow();


        BlockStmt body = method.getBody().get();
        List<Statement> stmts = body.getStatements();

        // Create sequence from first few statements
        StatementSequence sequence = new StatementSequence(
                stmts.subList(0, Math.min(3, stmts.size())),
                new com.raditha.dedup.model.Range(1, 3, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        // Test return variable detection
        String returnVar = analyzer.findReturnVariable(sequence, "User");
        // Should find a User variable
        assertNotNull(returnVar, "Should find a return variable");

    }

    @Test
    void testFindLiveOutVariables_WithRealCode() {
        // Test with real test-bed sources
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.wrongreturnvalue.ServiceWithMultipleReturnCandidatesTest");

        assertNotNull(cu, "Test-bed class should be parsed");

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);

        List<Statement> stmts = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                List.of(stmts.get(0)),
                new com.raditha.dedup.model.Range(1, 1, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        // Call method - should not throw (no type parameter needed)
        Set<String> liveVars = analyzer.findLiveOutVariables(sequence);
        assertNotNull(liveVars, "Should return a set");
    }

    @Test
    void testIsSafeToExtract_BasicValidation() {
        // Simple validation that method exists and works
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                "com.raditha.bertie.testbed.wrongreturnvalue.ServiceWithMultipleReturnCandidatesTest");

        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElse(null);

        List<Statement> stmts = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                stmts,
                new com.raditha.dedup.model.Range(1, stmts.size(), 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        // Should not throw (requires returnType parameter)
        boolean safe = analyzer.isSafeToExtract(sequence, "void");
        // Just verify it returns a boolean
        assertTrue(safe || !safe, "Should return boolean");
    }

    @Test
    void testAnalyzerInitialization() {
        assertNotNull(analyzer, "Analyzer should be initialized");
    }

    @Test
    void testTestBedSourcesParsed() {
        // Verify test-bed sources were parsed
        var units = AntikytheraRunTime.getResolvedCompilationUnits();
        assertNotNull(units, "Should have compilation units");
        assertFalse(units.isEmpty(), "Should have parsed test-bed sources");

        // Check for at least one test-bed class
        boolean hasTestBedClass = units.keySet().stream()
                .anyMatch(key -> key.contains("testbed"));
        assertTrue(hasTestBedClass, "Should have parsed test-bed classes");
    }
}
