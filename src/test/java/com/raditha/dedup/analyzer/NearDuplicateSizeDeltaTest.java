package com.raditha.dedup.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.model.SimilarityPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Near-duplicates whose statement counts differ by a small delta (e.g. an extra log line)
 * must still be detected when {@code max_size_delta} allows it, and must be rejected when
 * it is set to 0 (strict equal-length behaviour).
 */
class NearDuplicateSizeDeltaTest {

    private static final String SOURCE = """
            package demo;
            public class Orders {
                private java.util.List<String> log = new java.util.ArrayList<>();
                private java.util.Map<String, Integer> stock = new java.util.HashMap<>();

                public int placeOrder(String item, int qty) {
                    int available = stock.getOrDefault(item, 0);
                    int remaining = available - qty;
                    stock.put(item, remaining);
                    int total = qty * 10;
                    int discounted = total - 1;
                    return discounted;
                }

                public int placeOrderWithLog(String item, int qty) {
                    int available = stock.getOrDefault(item, 0);
                    int remaining = available - qty;
                    log.add("placing " + item);
                    stock.put(item, remaining);
                    int total = qty * 10;
                    int discounted = total - 1;
                    return discounted;
                }
            }
            """;

    @BeforeAll
    static void setUpClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
    }

    @AfterEach
    void resetSettings() {
        Settings.setProperty("duplication_detector_cli", new java.util.HashMap<String, Object>());
    }

    private DuplicationReport analyze(int maxSizeDelta) {
        Settings.setProperty("duplication_detector_cli",
                new java.util.HashMap<>(Map.of("max_size_delta", maxSizeDelta, "min_lines", 5)));
        CompilationUnit cu = StaticJavaParser.parse(SOURCE);
        Path path = Paths.get("Orders.java");
        cu.setStorage(path);
        return new DuplicationAnalyzer(Map.of("demo.Orders", cu)).analyzeFile(cu, path);
    }

    @Test
    void extraStatementIsDetectedWhenDeltaAllowed() {
        DuplicationReport report = analyze(1);

        assertTrue(report.hasDuplicates(), "one inserted log line should not hide the duplicate");
        SimilarityPair pair = report.duplicates().stream()
                .filter(p -> p.seq1().statements().size() != p.seq2().statements().size())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a pair with differing statement counts"));

        assertTrue(pair.similarity().overallScore() >= 0.75,
                "score was " + pair.similarity().overallScore());
        assertTrue(pair.similarity().lcsScore() > 0.8);
        assertTrue(pair.similarity().structuralScore() > 0.8,
                "aligned structural score should not collapse on a shifted position");
        assertFalse(pair.similarity().canRefactor(),
                "size-mismatched pairs are report-only, never auto-refactored");
    }

    @Test
    void strictModeRejectsDifferingStatementCounts() {
        DuplicationReport report = analyze(0);

        assertTrue(report.duplicates().stream()
                .allMatch(p -> p.seq1().statements().size() == p.seq2().statements().size()),
                "max_size_delta=0 must keep the old equal-length gate");
    }
}
