package com.raditha.dedup;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossFileDuplicationTest {

    private DuplicationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.moderate());
    }

    @Test
    void testCrossFileDuplication() {
        String code1 = """
                class ClassA {
                    void commonMethod() {
                        System.out.println("Start");
                        int x = 10;
                        int y = 20;
                        int sum = x + y;
                        System.out.println("Sum: " + sum);
                        System.out.println("End");
                    }
                }
                """;

        String code2 = """
                class ClassB {
                    void duplicateMethod() {
                        System.out.println("Start");
                        int x = 10;
                        int y = 20;
                        int sum = x + y;
                        System.out.println("Sum: " + sum);
                        System.out.println("End");
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        // Set storage for project analysis
        cu1.setStorage(Paths.get("src/main/java/ClassA.java"));
        cu2.setStorage(Paths.get("src/main/java/ClassB.java"));

        Map<String, CompilationUnit> projectCUs = new HashMap<>();
        projectCUs.put("ClassA", cu1);
        projectCUs.put("ClassB", cu2);

        List<DuplicationReport> reports = analyzer.analyzeProject(projectCUs);

        // We expect duplicates to be found
        int totalDuplicates = reports.stream().mapToInt(DuplicationReport::getDuplicateCount).sum();
        assertTrue(totalDuplicates > 0, "Should find cross-file duplicates");

        // Verify that reports refer to correct files
        boolean foundA = false;
        boolean foundB = false;

        for (DuplicationReport report : reports) {
            if (report.sourceFile().endsWith("ClassA.java")) {
                if (report.hasDuplicates()) foundA = true;
            }
            if (report.sourceFile().endsWith("ClassB.java")) {
                if (report.hasDuplicates()) foundB = true;
            }
        }

        assertTrue(foundA, "ClassA should have duplicates reported");
        assertTrue(foundB, "ClassB should have duplicates reported");
    }
}
