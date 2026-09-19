package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ASTVariationAnalyzerTest {

    private ASTVariationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ASTVariationAnalyzer();
    }

    @Test
    void testNoVariations() {
        String code1 = """
                class Test {
                    void method1(String userName) {
                        logger.info("Starting: " + userName);
                        user.setName(userName);
                    }
                }
                """;

        String code2 = """
                class Test {
                    void method2(String userName) {
                        logger.info("Starting: " + userName);
                        user.setName(userName);
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, ContainerType.METHOD, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, ContainerType.METHOD, cu2, null);

        VariationAnalysis result = analyzer.analyzeVariations(seq1, seq2, cu1);

        // No variations - code is identical
        assertEquals(0, result.varyingExpressions().size());

        // Should find userName as variable reference
        assertTrue(result.variableReferences().size() > 0);
        assertTrue(result.variableReferences().stream()
                .anyMatch(ref -> ref.name().equals("userName")));
    }

    @Test
    void testLiteralVariation() {
        String code1 = """
                class Test {
                    void method1() {
                        user.setName("Alice");
                    }
                }
                """;

        String code2 = """
                class Test {
                    void method2() {
                        user.setName("Bob");
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, ContainerType.METHOD, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, ContainerType.METHOD, cu2, null);

        VariationAnalysis result = analyzer.analyzeVariations(seq1, seq2, cu1);

        // Should find variation in string literal
        assertTrue(result.varyingExpressions().size() > 0);

        // Should find variation with "Alice" vs "Bob"
        boolean foundVariation = result.varyingExpressions().stream()
                .anyMatch(v -> v.expr1().toString().contains("Alice") &&
                        v.expr2().toString().contains("Bob"));
        assertTrue(foundVariation);
    }

    @Test
    void testVariableReferenceDetection() {
        String code = """
                class Test {
                    void method(String userName, int age) {
                        user.setName(userName);
                        user.setAge(age);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration m = cu.findFirst(MethodDeclaration.class).get();

        StatementSequence seq = new StatementSequence(m.getBody().get().getStatements(), null, 0, m, ContainerType.METHOD, cu, null);

        // Analyze against itself (no variations)
        VariationAnalysis result = analyzer.analyzeVariations(seq, seq, cu);

        // Should find both userName and age as variable references
        assertTrue(result.variableReferences().stream()
                .anyMatch(ref -> ref.name().equals("userName")));
        assertTrue(result.variableReferences().stream()
                .anyMatch(ref -> ref.name().equals("age")));
    }

    @Test
    void testExpressionsEquivalentIgnoresFormattingAndParentheses() {
        assertTrue(analyzer.expressionsEquivalent(
                StaticJavaParser.parseExpression("a + b"),
                StaticJavaParser.parseExpression("a+b")));
        assertTrue(analyzer.expressionsEquivalent(
                StaticJavaParser.parseExpression("a + (b)"),
                StaticJavaParser.parseExpression("(a + b)")));
    }

    @Test
    void testExpressionsEquivalentIgnoresComments() {
        Expression withComment = StaticJavaParser.parseBlock("{ foo(1, // c\n 2); }")
                .findFirst(ExpressionStmt.class)
                .orElseThrow()
                .getExpression();

        assertTrue(analyzer.expressionsEquivalent(
                withComment,
                StaticJavaParser.parseExpression("foo(1, 2)")));
    }

    @Test
    void testExpressionsEquivalentDetectsStructuralDifferences() {
        assertFalse(analyzer.expressionsEquivalent(
                StaticJavaParser.parseExpression("a + b"),
                StaticJavaParser.parseExpression("a - b")));
        assertFalse(analyzer.expressionsEquivalent(
                StaticJavaParser.parseExpression("foo(1)"),
                StaticJavaParser.parseExpression("foo(2)")));
        assertFalse(analyzer.expressionsEquivalent(
                StaticJavaParser.parseExpression("x.y()"),
                StaticJavaParser.parseExpression("x.z()")));
    }

    @Test
    void testWholeStatementFormattingAndParenthesesAreEquivalent() {
        String code1 = """
                class Test {
                    void method1(String userName) {
                        logger.info("Starting: " + userName);
                    }
                }
                """;
        String code2 = """
                class Test {
                    void method2(String userName) {
                        logger.info(("Starting: " + userName));
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);
        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class).orElseThrow();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class).orElseThrow();

        StatementSequence seq1 = new StatementSequence(m1.getBody().orElseThrow().getStatements(),
                null, 0, m1, ContainerType.METHOD, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().orElseThrow().getStatements(),
                null, 0, m2, ContainerType.METHOD, cu2, null);

        assertEquals(0, analyzer.analyzeVariations(seq1, seq2, cu1).varyingExpressions().size());
    }
}
