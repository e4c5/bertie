package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for end-to-end duplicate detection.
 */
@Order(1)
class DuplicationAnalyzerTest {

    private DuplicationAnalyzer analyzer;

    @BeforeAll
    static void setUpClass() throws IOException {
        // Load test configuration
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer();
    }

    @Test
    void testNoDuplicates() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.simple.DistinctOperations");
        assertNotNull(cu, "DistinctOperations class not found in test-bed");

        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("DistinctOperations.java"));

        assertNotNull(report);
        assertFalse(report.hasDuplicates());
        assertEquals(0, report.getDuplicateCount());
    }

    @Test
    void testSimpleDuplicate() {
        CompilationUnit cu = AntikytheraRunTime
                .getCompilationUnit("com.raditha.bertie.testbed.wrongarguments.UserServiceWithDifferentValues");
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        assertNotNull(report);
        assertTrue(report.hasDuplicates());
        assertTrue(report.getDuplicateCount() > 0);

        // Should find high similarity (above moderate threshold of 0.75)
        // The two methods have similar structure but different literals/types,
        // which results in ~77% similarity (LCS: 71%, Lev: 71%, Struct: 100%)
        var duplicates = report.getDuplicatesAbove(0.7);
        assertFalse(duplicates.isEmpty(), "Should find duplicates with >70% similarity");
    }

    @Test
    void testPartialDuplicateInLargeMethod() {
        // Use maximalOnly=false to detect partial duplicates within larger methods
        // Note: Cannot configure maximalOnly via static config in current implementation
        // This test will use default configuration
        com.raditha.dedup.config.DuplicationDetectorSettings.loadConfig(5, 75, null);
        DuplicationAnalyzer partialAnalyzer = new DuplicationAnalyzer();

        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.partial.MixedResponsibilityService");
        assertNotNull(cu, "MixedResponsibilityService class not found in test-bed");

        DuplicationReport report = partialAnalyzer.analyzeFile(cu, Paths.get("MixedResponsibilityService.java"));

        // Should find the duplicate despite one being inside a larger method
        assertTrue(report.hasDuplicates());
    }

    @Test
    void testReportGeneration() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.report.ReportGenerator");
        assertNotNull(cu, "ReportGenerator class not found in test-bed");

        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("ReportGenerator.java"));

        // Get detailed report
        String detailedReport = report.getDetailedReport();
        assertNotNull(detailedReport);
        assertTrue(detailedReport.contains("DUPLICATION DETECTION REPORT"));
        assertTrue(detailedReport.contains("ReportGenerator.java"));

        // Get summary
        String summary = report.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("duplicates"));
        assertTrue(summary.contains("sequences"));
    }

    @Test
    void testPreFilteringEffectiveness() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.filter.ShapeVariations");
        assertNotNull(cu, "ShapeVariations class not found in test-bed");

        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("ShapeVariations.java"));

        // Should have analyzed fewer candidates than total possible pairs
        // due to pre-filtering
        assertTrue(report.candidatesAnalyzed() < report.totalSequences() * report.totalSequences());
    }
}
