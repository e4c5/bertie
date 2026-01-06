package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.Statement;
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

/**
 * DEBUG: See what duplicate is actually being detected
 */
class CallSiteDebugTest {

    private DuplicationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
    }

    @Test
    void testDebugWhatDuplicateWasFound() throws IOException {
        Path sourceFile = Paths
                .get("test-bed/src/main/java/com/raditha/bertie/testbed/callsite/ServiceWithObjectCreation.java");

        if (!Files.exists(sourceFile)) {
            System.out.println("SKIP: Test file doesn't exist");
            return;
        }

        String code = Files.readString(sourceFile);
        CompilationUnit cu = StaticJavaParser.parse(code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        System.out.println("\n=== DUPLICATE ANALYSIS DEBUG ===");
        System.out.println("Clusters found: " + report.clusters().size());

        for (int i = 0; i < report.clusters().size(); i++) {
            DuplicateCluster cluster = report.clusters().get(i);
            RefactoringRecommendation rec = cluster.recommendation();

            System.out.println("\n--- Cluster #" + (i + 1) + " ---");
            System.out.println("Primary sequence:");
            System.out.println("  Line range: " + cluster.primary().range().startLine() + "-"
                    + cluster.primary().range().endLine());
            System.out.println("  Statement count: " + cluster.primary().statements().size());
            System.out.println("  Statements:");
            for (int j = 0; j < cluster.primary().statements().size(); j++) {
                Statement stmt = cluster.primary().statements().get(j);
                String stmtStr = stmt.toString().replaceAll("\\n", " ");
                System.out.println("    " + (j + 1) + ". " + stmtStr.substring(0, Math.min(100, stmtStr.length())));
            }
            System.out.println("\nRecommendation:");
            System.out.println("  Method name: " + rec.getSuggestedMethodName());
            System.out.println("  Return type: " + rec.getSuggestedReturnType());
            System.out.println("  Parameters: " + rec.getSuggestedParameters().size());
        }
    }
}
