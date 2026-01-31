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
    private final String smallBoy =  """
                class Test {
                    abstract void abstractMethod();
                
                    void myMethod() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

    private final String bigBoy = """
                class Test {
                    abstract void abstractMethod();
                
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
    private StatementExtractor extractor;
    private Path testFile;

    @BeforeEach
    void setUp() {
        // Use maximalOnly=false to test all window sizes (original behavior)
        extractor = new StatementExtractor(5, 5, false);
        testFile = Paths.get("TestFile.java");
    }

    @Test
    void testExtractFromMethodWith5Statements() {
        CompilationUnit cu = StaticJavaParser.parse(smallBoy);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        // Should extract exactly 1 sequence (5 statements minimum, only 5 total)
        // Window sizes: 5
        assertEquals(1, sequences.size());
        assertEquals(5, sequences.get(0).statements().size());
    }

    @Test
    void testExtractFromMethodWith10Statements() {
        CompilationUnit cu = StaticJavaParser.parse(bigBoy);
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
    void testCustomMinStatements() {
        extractor = new StatementExtractor(3, 5, false); // Min 3 statements, maximalOnly=false

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
        CompilationUnit cu = StaticJavaParser.parse(smallBoy);
        List<StatementSequence> sequences = extractor.extractSequences(cu, testFile);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);

        // Check metadata
        assertNotNull(seq.range());
        assertNotNull(seq.containingCallable());
        assertEquals("myMethod", seq.containingCallable().getNameAsString());
        assertNotNull(seq.compilationUnit());
        // Path is now normalized at creation time, so compare normalized versions
        assertEquals(testFile.toAbsolutePath().normalize(), seq.sourceFilePath());
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

    // Tests for maximalOnly=true behavior
    
    @Test
    void testMaximalOnly_6Statements() {
        // With maximalOnly=true, only extract the maximal (longest) sequence at each position
        StatementExtractor maximalExtractor = new StatementExtractor(5, 5, true);
        
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
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu, testFile);

        // With maximalOnly=true:
        // Position 0: extract [0-5] (6 statements - maximal)
        // Position 1: extract [1-5] (5 statements - maximal)
        // Total: 2 sequences (much less than 3 with maximalOnly=false)
        assertEquals(2, sequences.size());
        assertEquals(6, sequences.get(0).statements().size()); // First is maximal
        assertEquals(5, sequences.get(1).statements().size()); // Second is also maximal from position 1
    }
    
    @Test
    void testMaximalOnly_10Statements() {
        StatementExtractor maximalExtractor = new StatementExtractor(5, 5, true);

        CompilationUnit cu = StaticJavaParser.parse(bigBoy);
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu, testFile);

        // With maximalOnly=true and maxWindowGrowth=5:
        // Position 0-4: extract 10 statements (min 5 + growth 5)
        // Position 5: extract 5 statements (only 5 remaining)
        // Total: 6 sequences (much less than 21 with maximalOnly=false)
        assertEquals(6, sequences.size());
        
        // First sequence should be maximal (10 statements)
        assertEquals(10, sequences.get(0).statements().size());
        
        // Last sequence should have 5 statements
        assertEquals(5, sequences.get(5).statements().size());
    }
    
    @Test
    void testMaximalOnly_WithSmallMaxWindowGrowth() {
        // With smaller maxWindowGrowth, maximal sequences are smaller
        StatementExtractor maximalExtractor = new StatementExtractor(5, 2, true);
        
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
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu, testFile);

        // With maxWindowGrowth=2: max window size is 5+2=7
        // Position 0-1: extract 7 statements
        // Position 2-3: extract 6, 5 statements respectively
        // Total: 4 sequences
        assertEquals(4, sequences.size());
        assertEquals(7, sequences.get(0).statements().size());
    }
}
