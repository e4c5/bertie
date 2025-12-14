package com.raditha.dedup.metrics;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsExporter - CSV and JSON export functionality.
 */
class MetricsExporterTest {

    @TempDir
    Path tempDir;

    private MetricsExporter exporter;
    private DuplicationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        exporter = new MetricsExporter();
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
    }

    @Test
    void testBuildMetrics() throws IOException {
        String code = """
                class TestClass {
                    void method1() {
                        String x = "test";
                        System.out.println(x);
                    }
                    void method2() {
                        String x = "test";
                        System.out.println(x);
                    }
                }
                """;

        Path testFile = tempDir.resolve("TestClass.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(List.of(report), "test-project");

        assertNotNull(metrics);
        assertEquals("test-project", metrics.projectName());
        assertEquals(1, metrics.totalFiles());
        assertNotNull(metrics.timestamp());
        assertTrue(metrics.files().size() > 0);
    }

    @Test
    void testCsvExport() throws IOException {
        String code = """
                class SimpleClass {
                    void duplicate1() {
                        int value = 42;
                    }
                    void duplicate2() {
                        int value = 42;
                    }
                }
                """;

        Path testFile = tempDir.resolve("SimpleClass.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(List.of(report), "test");

        Path csvPath = tempDir.resolve("metrics.csv");
        exporter.exportToCsv(metrics, csvPath);

        assertTrue(Files.exists(csvPath), "CSV file should be created");

        String csv = Files.readString(csvPath);
        assertTrue(csv.contains("# Project Summary"), "Should have summary section");
        assertTrue(csv.contains("# Per-File Metrics"), "Should have per-file section");
        assertTrue(csv.contains("timestamp,project,total_files"), "Should have summary header");
        assertTrue(csv.contains("file,duplicates,clusters"), "Should have file header");
        assertTrue(csv.contains("SimpleClass.java"), "Should include filename");
    }

    @Test
    void testJsonExport() throws IOException {
        String code = """
                class JsonTest {
                    void test1() { int x = 1; }
                    void test2() { int x = 1; }
                }
                """;

        Path testFile = tempDir.resolve("JsonTest.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(List.of(report), "json- project");

        Path jsonPath = tempDir.resolve("metrics.json");
        exporter.exportToJson(metrics, jsonPath);

        assertTrue(Files.exists(jsonPath), "JSON file should be created");

        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"timestamp\":"), "Should have timestamp");
        assertTrue(json.contains("\"project\":"), "Should have project name");
        assertTrue(json.contains("\"summary\":"), "Should have summary section");
        assertTrue(json.contains("\"files\":"), "Should have files array");
        assertTrue(json.contains("\"fileName\": \"JsonTest.java\""), "Should include filename");
        assertTrue(json.contains("\"duplicateCount\":"), "Should have duplicate count");
    }

    @Test
    void testMultipleFiles() throws IOException {
        // Create two files with duplicates
        String code1 = """
                class File1 {
                    void dup() { String s = "x"; }
                }
                """;

        String code2 = """
                class File2 {
                    void dup() { String s = "x"; }
                }
                """;

        Path file1 = tempDir.resolve("File1.java");
        Path file2 = tempDir.resolve("File2.java");
        Files.writeString(file1, code1);
        Files.writeString(file2, code2);

        CompilationUnit cu1 = StaticJavaParser.parse(file1);
        CompilationUnit cu2 = StaticJavaParser.parse(file2);

        DuplicationReport report1 = analyzer.analyzeFile(cu1, file1);
        DuplicationReport report2 = analyzer.analyzeFile(cu2, file2);

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(
                List.of(report1, report2), "multi-file-test");

        assertEquals(2, metrics.totalFiles());
        assertEquals(2, metrics.files().size());
    }

    @Test
    void testEmptyReport() {
        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(List.of(), "empty");

        assertEquals(0, metrics.totalFiles());
        assertEquals(0, metrics.totalDuplicates());
        assertEquals(0, metrics.totalClusters());
        assertEquals(0, metrics.totalLOCReduction());
        assertEquals(0.0, metrics.averageSimilarity());
        assertTrue(metrics.files().isEmpty());
    }

    @Test
    void testFileMetricsAccuracy() throws IOException {
        // File with known duplicate pattern
        String code = """
                import org.junit.jupiter.api.Test;

                class MetricsTest {
                    @Test
                    void test1() {
                        String result = calculate(1, 2);
                        assertNotNull(result);
                    }

                    @Test
                    void test2() {
                        String result = calculate(3, 4);
                        assertNotNull(result);
                    }
                }
                """;

        Path testFile = tempDir.resolve("MetricsTest.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(List.of(report), "accuracy-test");

        if (!metrics.files().isEmpty()) {
            MetricsExporter.FileMetrics file = metrics.files().get(0);
            assertEquals("MetricsTest.java", file.fileName());
            assertTrue(file.avgSimilarity() >= 0.0 && file.avgSimilarity() <= 1.0);
        }
    }
}
