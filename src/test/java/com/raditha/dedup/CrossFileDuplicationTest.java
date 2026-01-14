package com.raditha.dedup;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
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

import static org.junit.jupiter.api.Assertions.*;

class CrossFileDuplicationTest {

    private DuplicationAnalyzer analyzer;

    @BeforeAll
    static void setUpClass() throws IOException {
        // Load test configuration to ensure test-bed is available
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);

        // Reset and parse all files including test-bed
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.moderate());
    }

    @Test
    void testCrossFileDuplication() {
        CompilationUnit cu1 = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.crossfile.InventoryService");
        CompilationUnit cu2 = AntikytheraRunTime.getCompilationUnit("com.raditha.bertie.testbed.crossfile.ShippingService");

        assertNotNull(cu1, "InventoryService not found in test-bed");
        assertNotNull(cu2, "ShippingService not found in test-bed");

        // Ensure storage is set for analyzeProject to work
        cu1.setStorage(java.nio.file.Paths.get("InventoryService.java"), java.nio.charset.StandardCharsets.UTF_8);
        cu2.setStorage(java.nio.file.Paths.get("ShippingService.java"), java.nio.charset.StandardCharsets.UTF_8);

        Map<String, CompilationUnit> projectCUs = new HashMap<>();
        projectCUs.put("com.raditha.bertie.testbed.crossfile.InventoryService", cu1);
        projectCUs.put("com.raditha.bertie.testbed.crossfile.ShippingService", cu2);

        List<DuplicationReport> reports = analyzer.analyzeProject(projectCUs);

        // We expect duplicates to be found
        int totalDuplicates = reports.stream().mapToInt(DuplicationReport::getDuplicateCount).sum();
        assertTrue(totalDuplicates > 0, "Should find cross-file duplicates");

        // Verify that reports refer to correct files
        boolean foundA = false;
        boolean foundB = false;

        for (DuplicationReport report : reports) {
            if (report.sourceFile().endsWith("InventoryService.java") && report.hasDuplicates()) {
                foundA = true;
            }
            if (report.sourceFile().endsWith("ShippingService.java") && report.hasDuplicates()) {
                foundB = true;
            }
        }

        assertTrue(foundA, "InventoryService should have duplicates reported");
        assertTrue(foundB, "ShippingService should have duplicates reported");
    }
}
