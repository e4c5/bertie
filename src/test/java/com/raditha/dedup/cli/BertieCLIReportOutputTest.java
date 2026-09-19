package com.raditha.dedup.cli;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BertieCLIReportOutputTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
        Map<String, Object> config = new HashMap<>();
        config.put("min_lines", 3);
        config.put("threshold", 0.75);
        Settings.setProperty("duplication_detector", config);
        Settings.setProperty("duplication_detector_cli", new HashMap<String, Object>());
    }

    @Test
    void printsDetailedTextReportForDuplicates() throws IOException {
        DuplicationReport report = duplicateReport();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BertieCLI cli = new BertieCLI(writerFor(output));

        cli.printTextReport(List.of(report));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("DUPLICATION DETECTION REPORT"));
        assertTrue(text.contains("SUMMARY"));
        assertTrue(text.contains("DUPLICATE #1"));
        assertTrue(text.contains("Found in:"));
        assertTrue(text.contains("Class:"));
        assertTrue(text.contains("REFACTORING OPPORTUNITIES:"));
    }

    @Test
    void printsNoDuplicatesTextReport() {
        DuplicationReport report = new DuplicationReport(
                tempDir.resolve("Empty.java"), List.of(), List.of(), 0, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new BertieCLI(writerFor(output)).printTextReport(List.of(report));

        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("No significant code duplication found!"));
    }

    @Test
    void printsJsonReport() throws IOException {
        DuplicationReport report = duplicateReport();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new BertieCLI(writerFor(output)).printJsonReport(List.of(report));

        String json = output.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"filesAnalyzed\": 1"));
        assertTrue(json.endsWith("}"));
    }

    private DuplicationReport duplicateReport() throws IOException {
        String code = """
                class Report {
                    void first() {
                        int x = 1;
                        int y = 2;
                        int z = x + y;
                        System.out.println(z);
                        System.out.println("done");
                    }

                    void second() {
                        int x = 1;
                        int y = 2;
                        int z = x + y;
                        System.out.println(z);
                        System.out.println("done");
                    }
                }
                """;
        Path sourceFile = tempDir.resolve("Report.java");
        Files.writeString(sourceFile, code);
        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(sourceFile);
        DuplicationReport report = new DuplicationAnalyzer().analyzeFile(cu, sourceFile);
        assertTrue(report.hasDuplicates());
        return report;
    }

    private ConsoleWriter writerFor(ByteArrayOutputStream output) {
        return new ConsoleWriter(new PrintStream(output, true, StandardCharsets.UTF_8));
    }
}
