package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.model.RefactoringStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import java.util.Collections;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RefactoringBugReproductionTest {

    @TempDir
    Path tempDir;
    private DuplicationAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize Settings first to avoid NPE in other components
        Settings.loadConfigMap(new java.io.File("src/test/resources/analyzer-tests.yml"));
        AbstractCompiler.reset();

        // Set config via Settings
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("min_lines", 3);
        config.put("threshold", 0.75);
        Settings.setProperty("duplication_detector", config);
        
        // Pass empty map to constructor as we are analyzing single files
        analyzer = new DuplicationAnalyzer(java.util.Collections.emptyMap());
    }

    @Test
    void testReportGeneratorDetection() throws IOException {
        String code = """
            package com.raditha.bertie.testbed.report;

            public class ReportGenerator {
                void generateHeader() {
                    int x = 1;
                    int y = 2;
                    System.out.println(x + y);
                    System.out.println("done");
                    System.out.println("end");
                }
            
                void generateFooter() {
                    int x = 1;
                    int y = 2;
                    System.out.println(x + y);
                    System.out.println("done");
                    System.out.println("end");
                }
            }
            """;
        
        Path sourceFile = tempDir.resolve("ReportGenerator.java");
        Files.writeString(sourceFile, code);
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        cu.setStorage(sourceFile); // Ensure storage is set

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates in ReportGenerator with threshold 75");
        assertEquals(1, report.clusters().size(), "Should have 1 cluster");
    }

    @Test
    void testMultipleLiveOutVariablesWithLiterals() throws IOException {
         String code = """
                package com.example;
                public class MultiLiveOutLiterals {
                    public void process() {
                        int a = 1;
                        int b = 2;
                        System.out.println(a);
                        System.out.println(b);
                    }
                    
                    public void process2() {
                        int a = 1;
                        int b = 2;
                        System.out.println(a);
                        System.out.println(b);
                    }
                }
                """;
        Path sourceFile = tempDir.resolve("MultiLiveOutLiterals.java");
        Files.writeString(sourceFile, code);
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        cu.setStorage(sourceFile);
        
        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);
        assertFalse(report.clusters().isEmpty(), "Should detect duplicates");
        DuplicateCluster cluster = report.clusters().get(0);
        
        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "helper",
                List.of(),
                "void",
                "MultiLiveOutLiterals.java",
                1.0,
                2);

        ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
        
        // AFTER FIX: Should succeed (modified files not empty)
        assertFalse(result.modifiedFiles().isEmpty(), "Should succeed with multiple live-outs (literals should be redeclared)");
        assertTrue(result.description().contains("Extracted method"), "Should have description");
    }

    @Test
    public void testServiceWithImmutableConfigurationReproduction() throws IOException {
        String code = """
            package com.raditha.bertie.testbed.testisolation;
            public class ServiceWithImmutableConfiguration {
                public String processApiCall() {
                    String apiUrl = "https://api.example.com";
                    String apiVersion = "v1";
                    int timeout = 30;
                    System.out.println("Configuring API client");
                    System.out.println("URL: " + apiUrl + "/" + apiVersion);
                    
                    return String.format("API: %s/%s, Timeout: %d", apiUrl, apiVersion, timeout);
                }
                
                public int calculateTimeout() {
                    String apiUrl = "https://api.example.com";
                    String apiVersion = "v1";
                    int timeout = 30;
                    System.out.println("Configuring API client");
                    System.out.println("URL: " + apiUrl + "/" + apiVersion);
                    
                    return timeout * 2;
                }
            }
            """;
            
        Path sourceFile = tempDir.resolve("ServiceWithImmutableConfiguration.java");
        Files.writeString(sourceFile, code);
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        cu.setStorage(sourceFile);
        
        // Analyze to get clusters
        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);
        if (report.clusters().isEmpty()) {
             System.out.println("Warning: No duplicates detected in reproduction test!");
        }
        
        DuplicateCluster cluster = report.clusters().get(0);
        
        RefactoringRecommendation recommendation = new RefactoringRecommendation(
            RefactoringStrategy.EXTRACT_HELPER_METHOD, 
            "setupConfig", 
            Collections.emptyList(), 
            new com.github.javaparser.ast.type.VoidType(),
            "ServiceWithImmutableConfiguration.java", // targetLocation
            1.0, // confidence
            10, // estimatedLOCReduction
            null // primaryReturnVariable
        );

        ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
        
        if (result.modifiedFiles().isEmpty()) {
            System.out.println("Reproduction Failed Reason: " + result.description());
        }
        
        assertFalse(result.modifiedFiles().isEmpty(), "Refactoring failed for ServiceWithImmutableConfiguration structure");
    }

    @Test
    void testHelperMethodAbortedOnArgFailure() {
         // This test simulates a case where prepareReplacement fails (returns null)
         // and verifies that the helper method added to the class is removed.
         // This reproduces the "unused method" bug in AquariumServiceImplTest.
         
         String code = """
                package com.example;
                public class HelperLeak {
                    public void process() {
                        System.out.println("duplicate");
                    }
                    public void process2() {
                        System.out.println("duplicate");
                    }
                }
                """;
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        var type = cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class); // Class decl

        var method = cu.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class).orElseThrow();
        var statement = method.getBody().get().getStatements().get(0);
        
        var seq = new com.raditha.dedup.model.StatementSequence(
                new com.github.javaparser.ast.NodeList<>(statement),
                new com.raditha.dedup.model.Range(4, 25, 4, 35),
                0,
                method,
                cu,
                java.nio.file.Paths.get("HelperLeak.java"));
                
        // Create a recommendation that requires a parameter that CANNOT be resolved
        // This forces prepareReplacement to fail.
        var impossibleParam = new com.raditha.dedup.model.ParameterSpec(
             "impossible", 
             new com.github.javaparser.ast.type.PrimitiveType(com.github.javaparser.ast.type.PrimitiveType.Primitive.INT),
             List.of(), // Empty examples
             -1, // No variation index
             100, // Invalid line
             100  // Invalid col
        );

        var recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "leakedHelper", // Name we expect to NOT see
                List.of(impossibleParam),
                "void",
                null,
                1.0,
                1);

        var cluster = new DuplicateCluster(
                seq,
                List.of(new SimilarityPair(seq, seq, new SimilarityResult(1.0,1.0,1.0,1.0,0,0,null,null,true))), 
                recommendation,
                100);

        ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
        
        assertTrue(result.modifiedFiles().isEmpty(), "Refactoring should fail");
        
        // BUG FIX VERIFICATION: The helper method 'leakedHelper' should NOT exist
        boolean methodExists = cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
            .anyMatch(m -> m.getNameAsString().equals("leakedHelper"));
            
        // Assert FALSE (Fixed)
        assertFalse(methodExists, "Bug fixed: Helper method should be removed on failure");
    }
}
