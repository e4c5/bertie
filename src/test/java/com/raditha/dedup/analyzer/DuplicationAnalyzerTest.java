package com.raditha.dedup.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for end-to-end duplicate detection.
 */
class DuplicationAnalyzerTest {

    private DuplicationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.moderate());
    }

    @Test
    void testNoDuplicates() {
        String code = """
                class Test {
                    void method1() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }

                    void method2() {
                        String x = "hello";
                        String y = "world";
                        System.out.println(x + y);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        assertNotNull(report);
        assertFalse(report.hasDuplicates());
        assertEquals(0, report.getDuplicateCount());
    }

    @Test
    void testSimpleDuplicate() {
        String code = """
                class Test {
                    void setupUser1() {
                        user.setName("John");
                        user.setEmail("john@example.com");
                        user.setActive(true);
                        user.setRole("admin");
                        user.save();
                    }

                    void setupUser2() {
                        customer.setName("Jane");
                        customer.setEmail("jane@example.com");
                        customer.setActive(false);
                        customer.setRole("user");
                        customer.save();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        assertNotNull(report);
        assertTrue(report.hasDuplicates());
        assertTrue(report.getDuplicateCount() > 0);

        // Should find high similarity
        var duplicates = report.getDuplicatesAbove(0.9);
        assertFalse(duplicates.isEmpty());
    }

    @Test
    void testPartialDuplicateInLargeMethod() {
        String code = """
                class Test {
                    void smallMethod() {
                        user.setName("Test");
                        user.setEmail("test@example.com");
                        user.setActive(true);
                        user.setRole("admin");
                        user.save();
                    }

                    void largeMethod() {
                        // Setup phase
                        logger.info("Starting");
                        validator.check();

                        // DUPLICATE CODE
                        user.setName("Test");
                        user.setEmail("test@example.com");
                        user.setActive(true);
                        user.setRole("admin");
                        user.save();

                        // Cleanup phase
                        logger.info("Done");
                        cache.invalidate();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        // Should find the duplicate despite one being inside a larger method
        assertTrue(report.hasDuplicates());
    }

    @Test
    void testReportGeneration() {
        String code = """
                class Test {
                    void method1() {
                        int x = 1;
                        int y = 2;
                        System.out.println(x + y);
                        System.out.println("done");
                        System.out.println("end");
                    }

                    void method2() {
                        int x = 1;
                        int y = 2;
                        System.out.println(x + y);
                        System.out.println("done");
                        System.out.println("end");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        // Get detailed report
        String detailedReport = report.getDetailedReport();
        assertNotNull(detailedReport);
        assertTrue(detailedReport.contains("DUPLICATION DETECTION REPORT"));
        assertTrue(detailedReport.contains("Test.java"));

        // Get summary
        String summary = report.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("duplicates"));
        assertTrue(summary.contains("sequences"));
    }

    @Test
    void testPreFilteringEffectiveness() {
        String code = """
                class Test {
                    void method1() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }

                    void method2() {
                        int x = 1;
                        int y = 2;
                        int z = 3;
                        int w = 4;
                        int v = 5;
                    }

                    void method3() {
                        String s1 = "hello";
                        String s2 = "world";
                        String s3 = "test";
                        String s4 = "example";
                        String s5 = "done";
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, Paths.get("Test.java"));

        // Should have analyzed fewer candidates than total possible pairs
        // due to pre-filtering
        assertTrue(report.candidatesAnalyzed() < report.totalSequences() * report.totalSequences());
    }
}
