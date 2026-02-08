package com.raditha.dedup.integration;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.DuplicateCluster;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for duplicate detection across different container types.
 * Verifies that duplicates are detected when the same code appears in:
 * - Static initializers
 * - Instance initializers
 * - Methods
 * - Constructors
 * - Lambdas
 * - Anonymous class methods
 */
class CrossContainerDuplicateTest {

    private DuplicationAnalyzer analyzer;
    private Path testFile;

    @BeforeAll
    static void setUpClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer();
        testFile = Paths.get("CrossContainerTest.java");
    }

    @Test
    void testDuplicatesBetweenStaticInitializerAndMethod() {
        String code = """
                class Test {
                    static {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                    
                    void method() {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates between static initializer and method");
        assertEquals(1, report.clusters().size(), "Should have exactly one duplicate cluster");
        
        DuplicateCluster cluster = report.clusters().get(0);
        var sequences = cluster.allSequences();
        
        // Verify we have sequences from different container types
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.STATIC_INITIALIZER),
                "Should include sequence from static initializer");
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.METHOD),
                "Should include sequence from method");
    }

    @Test
    void testDuplicatesBetweenInstanceInitializerAndConstructor() {
        String code = """
                class Test {
                    {
                        String name = "default";
                        int age = 0;
                        boolean active = false;
                        String status = name + age + active;
                        System.out.println("Initialized: " + status);
                    }
                    
                    Test() {
                        String name = "default";
                        int age = 0;
                        boolean active = false;
                        String status = name + age + active;
                        System.out.println("Initialized: " + status);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates between instance initializer and constructor");
        
        DuplicateCluster cluster = report.clusters().get(0);
        var sequences = cluster.allSequences();
        
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.INSTANCE_INITIALIZER),
                "Should include sequence from instance initializer");
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.CONSTRUCTOR),
                "Should include sequence from constructor");
    }

    @Test
    void testDuplicatesBetweenMethodAndLambda() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    void regularMethod() {
                        String data = "input";
                        int count = 10;
                        String result = data.repeat(count);
                        System.out.println("Processing: " + result);
                        System.out.println("Done with count: " + count);
                    }
                    
                    void methodWithLambda() {
                        Consumer<String> processor = (input) -> {
                            String data = "input";
                            int count = 10;
                            String result = data.repeat(count);
                            System.out.println("Processing: " + result);
                            System.out.println("Done with count: " + count);
                        };
                        processor.accept("test");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates between method and lambda");
        
        DuplicateCluster cluster = report.clusters().get(0);
        var sequences = cluster.allSequences();
        
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.METHOD),
                "Should include sequence from regular method");
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.LAMBDA),
                "Should include sequence from lambda");
    }

    @Test
    void testDuplicatesAcrossMultipleContainerTypes() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    static {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                    
                    {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                    
                    Test() {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                    
                    void method() {
                        String prefix = "CONFIG_";
                        int version = 1;
                        String suffix = "_FINAL";
                        String config = prefix + version + suffix;
                        System.out.println("Configuration: " + config);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates across all container types");
        
        // All 4 occurrences should be in the same cluster
        DuplicateCluster cluster = report.clusters().get(0);
        assertEquals(4, cluster.allSequences().size(), "Should have 4 sequences in the cluster");
        
        var sequences = cluster.allSequences();
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.STATIC_INITIALIZER));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.INSTANCE_INITIALIZER));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.CONSTRUCTOR));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == ContainerType.METHOD));
    }

    @Test
    void testNoDuplicatesWithDifferentCode() {
        String code = """
                class Test {
                    static {
                        String x = "static";
                        int a = 1;
                        System.out.println(x + a);
                        System.out.println("more");
                        System.out.println("code");
                    }
                    
                    void method() {
                        String y = "method";
                        double b = 2.0;
                        System.out.println(y + b);
                        System.out.println("different");
                        System.out.println("things");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Code is different enough that it shouldn't be detected as duplicates
        // (different variable names, types, and string values)
        assertFalse(report.hasDuplicates(), "Should not detect duplicates with significantly different code");
    }

    @Test
    void testStaticContextPreserved() {
        String code = """
                class Test {
                    static {
                        String a = "1";
                        String b = "2";
                        String c = "3";
                        String d = "4";
                        String e = "5";
                    }
                    
                    static void staticMethod() {
                        String a = "1";
                        String b = "2";
                        String c = "3";
                        String d = "4";
                        String e = "5";
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        assertTrue(report.hasDuplicates());
        
        DuplicateCluster cluster = report.clusters().get(0);
        var sequences = cluster.allSequences();
        
        // Both should be in static context
        assertTrue(sequences.stream().allMatch(s -> s.isStaticContext()),
                "All sequences should be in static context");
    }
}
