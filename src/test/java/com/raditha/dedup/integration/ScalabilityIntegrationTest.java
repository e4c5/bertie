package com.raditha.dedup.integration;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalabilityIntegrationTest {

    @Test
    void comparePerformance() {
        // 1. Generate a large CompilationUnit
        int numMethods = 10000; // Enough to make O(N^2) painful
        String sourceCode = generateSourceCode(numMethods);

        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> res = parser.parse(sourceCode);
        if (!res.isSuccessful()) {
            throw new RuntimeException("Failed to parse generated code: " + res.getProblems());
        }
        CompilationUnit cu = res.getResult().orElseThrow();
        Path dummyPath = Paths.get("LargeFile.java");

        // 2. Measure Brute Force (LSH disabled)
        com.raditha.dedup.config.DuplicationDetectorSettings.loadConfig(5, 75, null);
        // Explicitly disable LSH for brute-force measurement
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cliConfig = (java.util.Map<String, Object>) 
            sa.com.cloudsolutions.antikythera.configuration.Settings.getProperty("duplication_detector_cli");
        if (cliConfig == null) {
            cliConfig = new java.util.HashMap<>();
        } else {
            cliConfig = new java.util.HashMap<>(cliConfig);
        }
        cliConfig.put("enable_lsh", false);
        sa.com.cloudsolutions.antikythera.configuration.Settings.setProperty("duplication_detector_cli", cliConfig);
        
        DuplicationAnalyzer analyzerBF = new DuplicationAnalyzer(Collections.emptyMap());

        // Warmup run to eliminate JVM startup effects
        analyzerBF.analyzeFile(cu, dummyPath);

        long startBF = System.currentTimeMillis();
        analyzerBF.analyzeFile(cu, dummyPath);
        long durationBF = System.currentTimeMillis() - startBF;

        // 3. Measure LSH (LSH enabled)
        com.raditha.dedup.config.DuplicationDetectorSettings.loadConfig(5, 75, null);
        // Explicitly enable LSH
        cliConfig = new java.util.HashMap<>(cliConfig);
        cliConfig.put("enable_lsh", true);
        sa.com.cloudsolutions.antikythera.configuration.Settings.setProperty("duplication_detector_cli", cliConfig);
        
        DuplicationAnalyzer analyzerLSH = new DuplicationAnalyzer(Collections.emptyMap());

        // Warmup run to eliminate JVM startup effects
        analyzerLSH.analyzeFile(cu, dummyPath);

        long startLSH = System.currentTimeMillis();
        analyzerLSH.analyzeFile(cu, dummyPath);
        long durationLSH = System.currentTimeMillis() - startLSH;

        double tolerance = 1.5; // Allow LSH to be up to 50% slower (conservative for small datasets)
        assertTrue(durationLSH <= durationBF * tolerance,
            "LSH was significantly slower than brute force. BF: %d ms, LSH: %d ms (%.2fx slower)"
                .formatted(durationBF, durationLSH, (double) durationLSH / durationBF));
    }

    private String generateSourceCode(int numMethods) {
        StringBuilder sb = new StringBuilder();
        sb.append("public class LargeFile {\n");

        // Templates to create some duplicates
        String[] templates = {
                "int a = 0; int b = 1; int c = a + b; System.out.println(c);",
                "String s = \"hello\"; if (s.length() > 5) { return; }",
                "for(int i=0; i<10; i++) { list.add(i); }",
                "try { process(); } catch(Exception e) { e.printStackTrace(); }"
        };

        for (int i = 0; i < numMethods; i++) {
            sb.append("    public void method").append(i).append("() {\n");

            // Mix of duplicates and unique content
            if (i % 10 == 0) {
                // Create a cluster of duplicates
                sb.append("        ").append(templates[0]).append("\n");
            } else if (i % 10 == 1) {
                sb.append("        ").append(templates[1]).append("\n");
            } else {
                // Unique-ish content
                sb.append("        int unique").append(i).append(" = ").append(i).append(";\n");
                sb.append("        System.out.println(unique").append(i).append(");\n");
            }
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
