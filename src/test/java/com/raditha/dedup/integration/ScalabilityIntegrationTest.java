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

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalabilityIntegrationTest {

    @Test
    void comparePerformance() {
        // 1. Generate a large CompilationUnit
        int numMethods = 500; // Enough to make O(N^2) measurable but not timeout
        String sourceCode = generateSourceCode(numMethods);

        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> res = parser.parse(sourceCode);
        if (!res.isSuccessful()) {
            throw new RuntimeException("Failed to parse generated code: " + res.getProblems());
        }
        CompilationUnit cu = res.getResult().orElseThrow();
        Path dummyPath = Paths.get("LargeFile.java");


        // 2. Measure Brute Force
        DuplicationConfig configBF = new DuplicationConfig(5, 0.75,
                com.raditha.dedup.config.SimilarityWeights.balanced(),
                false, java.util.List.of(), 5, true, true, false); // enableLSH = false

        DuplicationAnalyzer analyzerBF = new DuplicationAnalyzer(configBF, Collections.emptyMap());

        long startBF = System.currentTimeMillis();
        analyzerBF.analyzeFile(cu, dummyPath);
        long durationBF = System.currentTimeMillis() - startBF;

        // 3. Measure LSH
        DuplicationConfig configLSH = new DuplicationConfig(5, 0.75,
                com.raditha.dedup.config.SimilarityWeights.balanced(),
                false, java.util.List.of(), 5, true, true, true); // enableLSH = true

        DuplicationAnalyzer analyzerLSH = new DuplicationAnalyzer(configLSH, Collections.emptyMap());

        long startLSH = System.currentTimeMillis();
        analyzerLSH.analyzeFile(cu, dummyPath);
        long durationLSH = System.currentTimeMillis() - startLSH;
        System.out.println("LSH Time:         " + durationLSH + " ms");

        assertTrue(durationLSH <= durationBF, "LSH was slower. Overhead might dominate. %d vs %d".formatted(durationLSH, durationBF));
    }

    private String generateSourceCode(int numMethods) {
        StringBuilder sb = new StringBuilder();
        sb.append("public class LargeFile {\n");

        // Templates to create some duplicates
        // Note: Must exceed minLines=5 to be considered for extraction
        String[] templates = {
                """
                int a = 0;
                int b = 1;
                int c = a + b;
                System.out.println(c);
                System.out.println("Done");
                """,
                """
                String s = "hello";
                if (s.length() > 5) {
                    System.out.println("Too long");
                    return;
                }
                System.out.println("OK");
                """,
                """
                for(int i=0; i<10; i++) {
                    list.add(i);
                    System.out.println(i);
                    System.out.flush();
                }
                """,
                """
                try {
                    process();
                    System.out.println("Processed");
                } catch(Exception e) {
                    e.printStackTrace();
                    System.err.println("Error");
                }
                """
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
                // Unique-ish content but long enough to be scanned
                sb.append("        int unique").append(i).append(" = ").append(i).append(";\n");
                sb.append("        System.out.println(unique").append(i).append(");\n");
                sb.append("        System.out.println(\"More unique content\");\n");
                sb.append("        System.out.println(\"Even more unique content\");\n");
                sb.append("        System.out.println(\"Last line\");\n");
            }
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
