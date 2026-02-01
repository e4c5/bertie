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
        System.out.println("DEBUG: min_lines=" + com.raditha.dedup.config.DuplicationDetectorSettings.getMinLines());
        DuplicationReport report = analyzer.analyzeFile(cu, sourcePath);
        System.out.println("DEBUG: report.hasDuplicates=" + report.hasDuplicates());
        System.out.println("DEBUG: duplicates size=" + report.duplicates().size());
        
        assertTrue(report.hasDuplicates(), "Should detect duplicates in ConstructorReuseService");
        
        // Filter for the constructor cluster
        DuplicateCluster cluster = report.clusters().stream()
                .filter(c -> c.primary().containingCallable() instanceof com.github.javaparser.ast.body.ConstructorDeclaration)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No constructor cluster found"));

        engine = new RefactoringEngine(Path.of("."), RefactoringEngine.RefactoringMode.DRY_RUN, VerifyMode.NONE);
        
        // We expect Case 1: The entire body of no-arg ctor is in the parameterized ctor
        // Current implementation will likely use EXTRACT_HELPER_METHOD
        // We want to eventually see this uses 'this()'
        
        MethodExtractor refactorer = new MethodExtractor();
        MethodExtractor.RefactoringResult result = refactorer.refactor(cluster, cluster.recommendation());
        
        String refactored = result.modifiedFiles().values().iterator().next();
        System.out.println("Refactored ConstructorReuseService:\\n" + refactored);
        
        // After fix, this should contain this()
        // For now, it will probably contain a helper call
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
                System.out.println("Refactored TankConfigManager cluster:\\n" + refactored);
                
                // Case 2 Check: Ensure the first 'if' block is NOT missing if it's identical
                // In my baseline run, it WAS missing.
            }
        }
    }
}
