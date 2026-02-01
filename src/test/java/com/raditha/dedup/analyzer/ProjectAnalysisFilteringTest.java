package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAnalysisFilteringTest {

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
        // Clear any previous target class setting
        Map<String, Object> cliConfig = new HashMap<>();
        Settings.setProperty("duplication_detector_cli", cliConfig);
    }

    @Test
    void testAnalyzeProject_WithTargetClassFiltering() {
        // 1. Set target class
        String targetClass = "com.raditha.bertie.testbed.simple.DistinctOperations";
        Map<String, Object> cliConfig = new HashMap<>();
        cliConfig.put("target_class", targetClass);
        Settings.setProperty("duplication_detector_cli", cliConfig);

        // 2. Run analysis
        List<DuplicationReport> reports = analyzer.analyzeProject();

        // 3. Verify that we only got a report for the target class
        // Note: analyzeProject returns reports for files that were analyzed.
        // Even if no duplicates are found, it might return a report (depending on implementation details,
        // usually it returns reports for all files processed if they have sequences).
        // Let's check the size or the content.

        boolean foundTarget = false;
        boolean foundOther = false;

        for (DuplicationReport report : reports) {
            String path = report.sourceFile().toString();
            if (path.contains("DistinctOperations.java")) {
                foundTarget = true;
            } else {
                foundOther = true;
            }
        }

        assertTrue(foundTarget, "Should have analyzed DistinctOperations");
        assertFalse(foundOther, "Should NOT have analyzed other files");
    }

    @Test
    void testAnalyzeProject_NoFiltering() {
        // 1. Ensure no target class is set
        Settings.setProperty("duplication_detector_cli", new HashMap<>());

        // 2. Run analysis
        List<DuplicationReport> reports = analyzer.analyzeProject();

        // 3. Verify that we analyzed multiple files
        assertTrue(reports.size() > 1, "Should have analyzed multiple files without filtering");
    }
}
