package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2 context analysis enhancements:
 * - Lambda closure detection
 * - Outer class field access detection
 * - Static context handling
 */
class ContextAnalysisTest {

    private DataFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DataFlowAnalyzer();
    }

    // ========== Lambda Closure Tests ==========

    @Test
    void testLambdaCapturedVariables() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    void method() {
                        String captured = "value";
                        int alsoCapured = 42;
                        Consumer<String> c = (s) -> {
                            System.out.println(captured);
                            System.out.println(alsoCapured);
                            String local = "local";
                            System.out.println(local);
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LambdaExpr lambda = cu.findFirst(LambdaExpr.class).orElseThrow();

        StatementSequence seq = new StatementSequence(
                lambda.getBody().asBlockStmt().getStatements(),
                new Range(6, 10, 1, 1),
                0,
                lambda,
                ContainerType.LAMBDA,
                cu,
                Paths.get("Test.java")
        );

        Set<String> captured = LambdaClosureAnalyzer.findAllCapturedVariables(seq);

        assertTrue(captured.contains("captured"), "Should detect 'captured' as captured variable");
        assertTrue(captured.contains("alsoCapured"), "Should detect 'alsoCapured' as captured variable");
        assertFalse(captured.contains("local"), "Should not include 'local' - it's declared in lambda");
        assertFalse(captured.contains("s"), "Should not include 's' - it's a lambda parameter");
    }

    @Test
    void testLambdaContextInSequenceAnalysis() {
        String code = """
                import java.util.function.Consumer;
                class Test {
                    void method() {
                        String outer = "value";
                        Consumer<String> c = (s) -> {
                            System.out.println(outer);
                            System.out.println(s);
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LambdaExpr lambda = cu.findFirst(LambdaExpr.class).orElseThrow();

        StatementSequence seq = new StatementSequence(
                lambda.getBody().asBlockStmt().getStatements(),
                new Range(5, 8, 1, 1),
                0,
                lambda,
                ContainerType.LAMBDA,
                cu,
                Paths.get("Test.java")
        );

        DataFlowAnalyzer.SequenceAnalysis analysis = analyzer.analyzeSequenceVariables(seq);

        assertNotNull(analysis.context());
        assertEquals(ContainerType.LAMBDA, analysis.context().containerType());
        assertTrue(analysis.isCapturedVariable("outer"), "Should identify 'outer' as captured");
        assertFalse(analysis.isCapturedVariable("s"), "Lambda param 's' should not be captured");
    }

    // ========== Static Context Tests ==========

    @Test
    void testStaticInitializerContext() {
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
        var init = cu.findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).orElseThrow();

        StatementSequence seq = new StatementSequence(
                init.getBody().getStatements(),
                new Range(2, 8, 1, 1),
                0,
                init,
                ContainerType.STATIC_INITIALIZER,
                cu,
                Paths.get("Test.java")
        );

        DataFlowAnalyzer.SequenceAnalysis analysis = analyzer.analyzeSequenceVariables(seq);

        assertNotNull(analysis.context());
        assertTrue(analysis.context().isStaticContext(), "Static initializer should be in static context");
        assertEquals(ContainerType.STATIC_INITIALIZER, analysis.context().containerType());
    }

    @Test
    void testInstanceInitializerContext() {
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
        var init = cu.findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).orElseThrow();

        StatementSequence seq = new StatementSequence(
                init.getBody().getStatements(),
                new Range(2, 8, 1, 1),
                0,
                init,
                ContainerType.INSTANCE_INITIALIZER,
                cu,
                Paths.get("Test.java")
        );

        DataFlowAnalyzer.SequenceAnalysis analysis = analyzer.analyzeSequenceVariables(seq);

        assertNotNull(analysis.context());
        assertFalse(analysis.context().isStaticContext(), "Instance initializer should not be in static context");
        assertEquals(ContainerType.INSTANCE_INITIALIZER, analysis.context().containerType());
    }

    // ========== Outer Class Field Access Tests ==========

    @Test
    void testAnonymousClassOuterFieldAccess() {
        String code = """
                class Outer {
                    private String outerField = "value";
                    private int outerInt = 42;
                    
                    void method() {
                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                System.out.println(outerField);
                                System.out.println(outerInt);
                                String local = "local";
                                System.out.println(local);
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ObjectCreationExpr anon = cu.findFirst(ObjectCreationExpr.class).orElseThrow();
        MethodDeclaration runMethod = anon.getAnonymousClassBody().get().stream()
                .filter(bd -> bd instanceof MethodDeclaration)
                .map(bd -> (MethodDeclaration) bd)
                .findFirst().orElseThrow();

        StatementSequence seq = new StatementSequence(
                runMethod.getBody().get().getStatements(),
                new Range(8, 12, 1, 1),
                0,
                runMethod,
                ContainerType.ANONYMOUS_CLASS_METHOD,
                cu,
                Paths.get("Test.java")
        );

        Set<String> outerFields = OuterClassFieldAnalyzer.findOuterFieldAccess(seq);

        assertTrue(outerFields.contains("outerField"), "Should detect 'outerField' as outer field access");
        assertTrue(outerFields.contains("outerInt"), "Should detect 'outerInt' as outer field access");
        assertFalse(outerFields.contains("local"), "Should not include 'local' - it's declared locally");
    }

    @Test
    void testAnonymousClassContextInSequenceAnalysis() {
        String code = """
                class Outer {
                    private String field = "value";
                    
                    void method() {
                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                System.out.println(field);
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ObjectCreationExpr anon = cu.findFirst(ObjectCreationExpr.class).orElseThrow();
        MethodDeclaration runMethod = anon.getAnonymousClassBody().get().stream()
                .filter(bd -> bd instanceof MethodDeclaration)
                .map(bd -> (MethodDeclaration) bd)
                .findFirst().orElseThrow();

        StatementSequence seq = new StatementSequence(
                runMethod.getBody().get().getStatements(),
                new Range(7, 9, 1, 1),
                0,
                runMethod,
                ContainerType.ANONYMOUS_CLASS_METHOD,
                cu,
                Paths.get("Test.java")
        );

        DataFlowAnalyzer.SequenceAnalysis analysis = analyzer.analyzeSequenceVariables(seq);

        assertNotNull(analysis.context());
        assertEquals(ContainerType.ANONYMOUS_CLASS_METHOD, analysis.context().containerType());
        assertTrue(analysis.isOuterFieldAccess("field"), "Should identify 'field' as outer field access");
    }

    @Test
    void testRegularMethodHasNoOuterFieldAccess() {
        String code = """
                class Test {
                    private String field = "value";
                    
                    void method() {
                        System.out.println(field);
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();

        StatementSequence seq = new StatementSequence(
                method.getBody().get().getStatements(),
                new Range(4, 10, 1, 1),
                0,
                method,
                ContainerType.METHOD,
                cu,
                Paths.get("Test.java")
        );

        DataFlowAnalyzer.SequenceAnalysis analysis = analyzer.analyzeSequenceVariables(seq);

        // Regular methods don't have "outer" field access - field is in the same class
        assertTrue(analysis.context().outerFieldAccess().isEmpty(),
                "Regular method should not have outer field access");
    }
}
