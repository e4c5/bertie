package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests duplicate detection for object creation call sites.
 * Verifies that duplicate object initialization patterns are properly detected
 * and that appropriate refactoring recommendations are generated.
 */
class CallSiteDebugTest {

    private DuplicationAnalyzer analyzer;
    private Path testFile;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
        testFile = Paths.get("test-bed/src/main/java/com/raditha/bertie/testbed/callsite/ServiceWithObjectCreation.java");
    }

    @Test
    void testDuplicateObjectCreationIsDetected() throws IOException {
        // Given: A file with duplicate object initialization patterns
        assertTrue(Files.exists(testFile), "Test file should exist: " + testFile);

        String code = Files.readString(testFile);
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: We analyze the file for duplicates
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Then: At least one duplicate cluster should be detected
        assertNotNull(report, "Report should not be null");
        assertFalse(report.clusters().isEmpty(), "Should detect duplicate object creation patterns");

        DuplicateCluster cluster = report.clusters().get(0);
        assertNotNull(cluster, "First cluster should not be null");

        // Verify the cluster has a primary sequence
        assertNotNull(cluster.primary(), "Cluster should have a primary sequence");
        assertFalse(cluster.primary().statements().isEmpty(), "Primary sequence should contain statements");
    }

    @Test
    void testRefactoringRecommendationIsGenerated() throws IOException {
        // Given: A file with duplicate patterns
        String code = Files.readString(testFile);
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: We analyze and generate recommendations
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Then: Each cluster should have a refactoring recommendation
        for (DuplicateCluster cluster : report.clusters()) {
            RefactoringRecommendation rec = cluster.recommendation();

            assertNotNull(rec, "Cluster should have a refactoring recommendation");
            assertNotNull(rec.getSuggestedMethodName(), "Recommendation should have a method name");
            assertFalse(rec.getSuggestedMethodName().isEmpty(),
                    "Method name should not be empty");
            assertNotNull(rec.getSuggestedReturnType(), "Recommendation should have a return type");
            assertNotNull(rec.getSuggestedParameters(), "Recommendation should have parameters list");
        }
    }

    @Test
    void testDuplicateSequenceLinesAreValid() throws IOException {
        // Given: A parsed file
        String code = Files.readString(testFile);
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: We analyze for duplicates
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Then: Line ranges should be valid
        for (DuplicateCluster cluster : report.clusters()) {
            int startLine = cluster.primary().range().startLine();
            int endLine = cluster.primary().range().endLine();

            assertTrue(startLine > 0, "Start line should be positive");
            assertTrue(endLine >= startLine, "End line should be >= start line");
            assertTrue(endLine < 1000, "End line should be reasonable (file is ~100 lines)");
        }
    }
}
