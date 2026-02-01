package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.cli.VerifyMode;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorRefactoringIntegrationTest {
    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @BeforeAll
    static void setupClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);
        
        // Ensure lenient detection for testing
        Map<String, Object> detectorConfig = new HashMap<>();
        detectorConfig.put("min_lines", 4);
        detectorConfig.put("threshold", 75);
        detectorConfig.put("maximal_only", false);
        Settings.setProperty("duplication_detector", detectorConfig);

        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        // Collect all CUs for cross-file analysis
        Map<String, CompilationUnit> allCUs = new HashMap<>();
        // In a real run, this would be populated by scanning the project
        analyzer = new DuplicationAnalyzer(allCUs);
    }

    @Test
    void testConstructorReuseCase1() throws IOException {
        String className = "com.raditha.bertie.testbed.aquarium.service.ConstructorReuseService";
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
        assertNotNull(cu, "ConstructorReuseService should be in AntikytheraRunTime");
        
        Path sourcePath = Path.of("test-bed/src/main/java/com/raditha/bertie/testbed/aquarium/service/ConstructorReuseService.java");
        DuplicationReport report = analyzer.analyzeFile(cu, sourcePath);
        
        assertTrue(report.hasDuplicates(), "Should detect duplicates in ConstructorReuseService");
        
        // Filter for the constructor cluster
        DuplicateCluster cluster = report.clusters().stream()
                .filter(c -> c.primary().containingCallable() instanceof com.github.javaparser.ast.body.ConstructorDeclaration)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No constructor cluster found"));

        MethodExtractor refactorer = new MethodExtractor();
        MethodExtractor.RefactoringResult result = refactorer.refactor(cluster, cluster.recommendation());
        
        assertFalse(result.modifiedFiles().isEmpty(), "Refactoring should modify at least one file");
        String refactored = result.modifiedFiles().values().iterator().next();
        
        assertTrue(refactored.contains("private ") || refactored.contains("this("), 
                "Refactored code should contain either a helper method or a 'this()' call");
    }

    @Test
    void testTankConfigManagerCase2() throws IOException {
        String className = "com.raditha.bertie.testbed.aquarium.service.TankConfigManager";
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
        
        Path sourcePath = Path.of("test-bed/src/main/java/com/raditha/bertie/testbed/aquarium/service/TankConfigManager.java");
        DuplicationReport report = analyzer.analyzeFile(cu, sourcePath);
        
        assertTrue(report.hasDuplicates());
        
        // Find clusters in TankConfigManager constructors
        List<DuplicateCluster> constructorClusters = report.clusters().stream()
                .filter(c -> c.primary().containingCallable() != null && 
                             c.primary().containingCallable().getNameAsString().equals("TankConfigManager"))
                .toList();

        assertFalse(constructorClusters.isEmpty(), "Should find clusters in TankConfigManager constructors");
        
        MethodExtractor refactorer = new MethodExtractor();
        for (DuplicateCluster cluster : constructorClusters) {
            MethodExtractor.RefactoringResult result = refactorer.refactor(cluster, cluster.recommendation());
            if (!result.modifiedFiles().isEmpty()) {
                String refactored = result.modifiedFiles().values().iterator().next();
                
                assertTrue(refactored.contains("if"), "Refactored code should still contain the 'if' block logic in TankConfigManager");
            }
        }
    }
}
