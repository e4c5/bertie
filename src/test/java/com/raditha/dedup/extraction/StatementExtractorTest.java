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
import static com.raditha.dedup.model.ContainerType.*;

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Should extract exactly 1 sequence (5 statements minimum, only 5 total)
        // Window sizes: 5
        assertEquals(1, sequences.size());
        assertEquals(5, sequences.get(0).statements().size());
    }

    @Test
    void testExtractFromMethodWith10Statements() {
        CompilationUnit cu = StaticJavaParser.parse(bigBoy);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Should extract 1 sequence (min 3, exactly 3 total)
        assertEquals(1, sequences.size());
        assertEquals(3, sequences.get(0).statements().size());
    }

    @Test
    void testSequenceHasCorrectMetadata() {
        CompilationUnit cu = StaticJavaParser.parse(smallBoy);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);

        // Check metadata
        assertNotNull(seq.range());
        assertTrue(seq.getContainingCallable().isPresent());
        assertEquals("myMethod", seq.getContainerName());
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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu);

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
        cu.setStorage(testFile);
        List<StatementSequence> sequences = maximalExtractor.extractSequences(cu);

        // With maxWindowGrowth=2: max window size is 5+2=7
        // Position 0-1: extract 7 statements
        // Position 2-3: extract 6, 5 statements respectively
        // PLUS: Special Case extracts the full 8 statements (exceeding window growth to ensure full match)
        // Total: 4 + 1 = 5 sequences
        assertEquals(5, sequences.size());
        assertEquals(8, sequences.get(0).statements().size()); // Special Case is added FIRST
        // And the first windowed sequence (which was at index 0 before) is now at index 1
        assertEquals(7, sequences.get(1).statements().size());
    }

    // ========== Tests for new container types ==========

    @Test
    void testExtractFromStaticInitializer() {
        String code = """
                class Test {
                    static {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);
        assertEquals(5, seq.statements().size());
        assertEquals(STATIC_INITIALIZER, seq.containerType());
        assertEquals("<static-init>", seq.getContainerName());
        assertTrue(seq.isStaticContext());
    }

    @Test
    void testExtractFromInstanceInitializer() {
        String code = """
                class Test {
                    {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);
        assertEquals(5, seq.statements().size());
        assertEquals(INSTANCE_INITIALIZER, seq.containerType());
        assertEquals("<instance-init>", seq.getContainerName());
        assertFalse(seq.isStaticContext());
    }

    @Test
    void testExtractFromLambdaBlock() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    void method() {
                        Consumer<String> c = (s) -> {
                            int a = 1;
                            int b = 2;
                            int c = 3;
                            int d = 4;
                            int e = 5;
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Should extract from both the method (1 statement) and the lambda (5 statements)
        // Method has only 1 statement so won't be extracted
        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);
        assertEquals(5, seq.statements().size());
        assertEquals(LAMBDA, seq.containerType());
        assertTrue(seq.getContainerName().startsWith("<lambda@"));
    }

    @Test
    void testExtractFromAnonymousClassMethod() {
        String code = """
                class Test {
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            int a = 1;
                            int b = 2;
                            int c = 3;
                            int d = 4;
                            int e = 5;
                        }
                    };
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        assertEquals(1, sequences.size());
        StatementSequence seq = sequences.get(0);
        assertEquals(5, seq.statements().size());
        assertEquals(ANONYMOUS_CLASS_METHOD, seq.containerType());
        assertEquals("run@anonymous", seq.getContainerName());
    }

    @Test
    void testExtractFromMultipleContainerTypes() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    static {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                    }
                    
                    {
                        int x = 1;
                        int y = 2;
                        int z = 3;
                        int w = 4;
                        int v = 5;
                    }
                    
                    void method() {
                        int m1 = 1;
                        int m2 = 2;
                        int m3 = 3;
                        int m4 = 4;
                        int m5 = 5;
                    }
                    
                    Test() {
                        int c1 = 1;
                        int c2 = 2;
                        int c3 = 3;
                        int c4 = 4;
                        int c5 = 5;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Should extract from: static init, instance init, method, constructor
        assertEquals(4, sequences.size());
        
        // Verify we have one of each container type
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == STATIC_INITIALIZER));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == INSTANCE_INITIALIZER));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == METHOD));
        assertTrue(sequences.stream().anyMatch(s -> s.containerType() == CONSTRUCTOR));
    }

    @Test
    void testLambdaExpressionNotBlock() {
        // Lambda with expression body (not block) should NOT be extracted
        String code = """
                import java.util.function.Function;
                class Test {
                    Function<Integer, Integer> f = (x) -> x + 1;
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Expression lambda has no block, so nothing to extract
        assertEquals(0, sequences.size());
    }

    @Test
    void testNestedLambdasExtracted() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    void method() {
                        Consumer<String> outer = (s) -> {
                            Consumer<String> inner = (t) -> {
                                int a = 1;
                                int b = 2;
                                int c = 3;
                                int d = 4;
                                int e = 5;
                            };
                            inner.accept(s);
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.setStorage(testFile);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        // Inner lambda has 5 statements, outer lambda has 2 (not enough)
        assertEquals(1, sequences.size());
        assertEquals(LAMBDA, sequences.get(0).containerType());
        assertEquals(5, sequences.get(0).statements().size());
    }
}
