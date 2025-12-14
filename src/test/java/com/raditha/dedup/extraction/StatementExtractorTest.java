package com.raditha.dedup.extraction;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StatementExtractor.
 */
class StatementExtractorTest {

    private StatementExtractor extractor;
    private Path testFile;

    @BeforeEach
    void setUp() {
        extractor = new StatementExtractor(); // Default min 5 statements
        testFile = Paths.get("TestFile.java");
    }

    @Test
    void testExtractFromMethodWith5Statements() {
        String code = """
                class Test {
                    void method() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should extract exactly 1 sequence (5 statements minimum, only 5 total)
        // Window sizes: 5
        assertEquals(1, sequences.size());
        assertEquals(5, sequences.get(0).statements().size());
    }

    @Test
    void testExtractFromMethodWith6Statements() {
        String code = """
                class Test {
                    void method() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                        int f = 6;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should extract 3 sequences:
        // - [0-4] (5 statements)
        // - [1-5] (5 statements)
        // - [0-5] (6 statements)
        assertEquals(3, sequences.size());
    }

    @Test
    void testExtractFromMethodWith10Statements() {
        String code = """
                class Test {
                    void method() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                        int f = 6;
                        int g = 7;
                        int h = 8;
                        int i = 9;
                        int j = 10;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // 10 statements, min 5
        // Possible start positions: 0-5 (6 positions)
        // For each start, windows from size 5 to (10 - start)
        // Total: 6 + 5 + 4 + 3 + 2 + 1 = 21 sequences
        assertEquals(21, sequences.size());
    }

    @Test
    void testSkipsMethodsWithFewerThan5Statements() {
        String code = """
                class Test {
                    void smallMethod() {
                        int a = 1;
                        int b = 2;
                    }

                    void largeMethod() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should only extract from largeMethod (1 sequence)
        assertEquals(1, sequences.size());
        assertEquals(5, sequences.get(0).statements().size());
    }

    @Test
    void testExtractFromMultipleMethods() {
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
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Each method contributes 1 sequence
        assertEquals(2, sequences.size());
    }

    @Test
    void testSkipsAbstractMethods() {
        String code = """
                abstract class Test {
                    abstract void abstractMethod();

                    void concreteMethod() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should only extract from concreteMethod
        assertEquals(1, sequences.size());
    }

    @Test
    void testCustomMinStatements() {
        extractor = new StatementExtractor(3); // Min 3 statements

        String code = """
                class Test {
                    void method() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should extract 1 sequence (min 3, exactly 3 total)
        assertEquals(1, sequences.size());
        assertEquals(3, sequences.get(0).statements().size());
    }

    @Test
    void testSequenceHasCorrectMetadata() {
        String code = """
                class Test {
                    void myMethod() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);

        // Check metadata
        assertNotNull(seq.range());
        assertNotNull(seq.containingMethod());
        assertEquals("myMethod", seq.containingMethod().getNameAsString());
        assertNotNull(seq.compilationUnit());
        assertEquals(testFile, seq.sourceFilePath());
    }

    @Test
    void testEmptyClass() {
        String code = """
                class Test {
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        assertEquals(0, sequences.size());
    }

    @Test
    void testInvalidMinStatements() {
        assertThrows(IllegalArgumentException.class, () -> {
            new StatementExtractor(0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new StatementExtractor(-1);
        });
    }
}
