package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import com.raditha.dedup.model.StatementSequence;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorDuplicatesReproductionTest {

    @BeforeAll
    static void setupClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);
        
        Map<String, Object> detectorConfig = new HashMap<>();
        detectorConfig.put("min_lines", 3);
        detectorConfig.put("threshold", 75);
        detectorConfig.put("maximal_only", false);
        Settings.setProperty("duplication_detector", detectorConfig);

        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testConstructorDuplicatesRefactoring() throws IOException {
        String className = "com.raditha.bertie.testbed.partial.ConstructorDuplicates";
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(className);
        assertNotNull(cu, "ConstructorDuplicates should be in AntikytheraRunTime");
        
        Path sourcePath = Path.of("test-bed/src/main/java/com/raditha/bertie/testbed/partial/ConstructorDuplicates.java");
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer();
        DuplicationReport report = analyzer.analyzeFile(cu, sourcePath);
        
        assertTrue(report.hasDuplicates(), "Should detect duplicates in ConstructorDuplicates");
        
        // Find cluster for No-arg constructor
        DuplicateCluster cluster1 = report.clusters().stream()
                .filter(c -> c.allSequences().stream().anyMatch(s -> s.containingCallable().getParameters().isEmpty()))
                .findFirst()
                .orElse(null);

        // Find cluster for Sequence 2 (capacity/factor)
        DuplicateCluster cluster2 = report.clusters().stream()
                .filter(c -> c.allSequences().stream().anyMatch(s -> s.containingCallable().toString().contains("int capacity")))
                .findFirst()
                .orElse(null);

        DuplicateCluster clusterToRefactor = cluster2 != null ? cluster2 : cluster1;
        assertNotNull(clusterToRefactor, "Should find at least one cluster");
        
        MethodExtractor refactorer = new MethodExtractor();
        MethodExtractor.RefactoringResult result = refactorer.refactor(clusterToRefactor, clusterToRefactor.recommendation());
        
        assertFalse(result.modifiedFiles().isEmpty(), "Refactoring should modify at least one file. Description: " + result.description());
        
        String refactored = result.modifiedFiles().values().iterator().next();
        assertNotNull(refactored, "Refactored code should not be null");
        
        assertTrue(refactored.contains("this(\"default\")") || refactored.contains("this("), 
                "Should use constructor delegation (this(...))");
    }
}
