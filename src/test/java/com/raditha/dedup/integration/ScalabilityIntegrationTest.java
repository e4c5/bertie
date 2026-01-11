package com.raditha.dedup.integration;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

public class ScalabilityIntegrationTest {

    @Test
    public void comparePerformance() {
        // 1. Generate a large CompilationUnit
        int numMethods = 10000; // Enough to make O(N^2) painful
        System.out.println("Generating source with " + numMethods + " methods...");
        String sourceCode = generateSourceCode(numMethods);

        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> res = parser.parse(sourceCode);
        if (!res.isSuccessful()) {
            throw new RuntimeException("Failed to parse generated code: " + res.getProblems());
        }
        CompilationUnit cu = res.getResult().get();
        Path dummyPath = Paths.get("LargeFile.java");

        System.out.println("Benchmarking analyzeFile()...");

        // 2. Measure Brute Force
        DuplicationConfig configBF = new DuplicationConfig(5, 0.75,
                com.raditha.dedup.config.SimilarityWeights.balanced(),
                false, java.util.List.of(), 5, true, true, false); // enableLSH = false

        DuplicationAnalyzer analyzerBF = new DuplicationAnalyzer(configBF, Collections.emptyMap());

        long startBF = System.currentTimeMillis();
        analyzerBF.analyzeFile(cu, dummyPath);
        long durationBF = System.currentTimeMillis() - startBF;
        System.out.println("Brute Force Time: " + durationBF + " ms");

        // 3. Measure LSH
        DuplicationConfig configLSH = new DuplicationConfig(5, 0.75,
                com.raditha.dedup.config.SimilarityWeights.balanced(),
                false, java.util.List.of(), 5, true, true, true); // enableLSH = true

        DuplicationAnalyzer analyzerLSH = new DuplicationAnalyzer(configLSH, Collections.emptyMap());

        long startLSH = System.currentTimeMillis();
        analyzerLSH.analyzeFile(cu, dummyPath);
        long durationLSH = System.currentTimeMillis() - startLSH;
        System.out.println("LSH Time:         " + durationLSH + " ms");

        if (durationLSH < durationBF) {
            double speedup = (double) durationBF / (durationLSH == 0 ? 1 : durationLSH);
            System.out.println("SUCCESS: LSH was " + String.format("%.2f", speedup) + "x faster.");
        } else {
            System.out.println("WARNING: LSH was slower. Overhead might dominate.");
        }
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
