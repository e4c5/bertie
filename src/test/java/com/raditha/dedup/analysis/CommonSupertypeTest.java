package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VaryingExpression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommonSupertypeTest {

    private ASTVariationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        analyzer = new ASTVariationAnalyzer();
    }

    @Test
    void testCommonSupertypeIntegerDouble() {
        String code1 = """
                class Test {
                    void method1(Integer i) {
                        print(i);
                    }
                    void print(Number n) {}
                }
                """;

        String code2 = """
                class Test {
                    void method2(Double d) {
                        print(d);
                    }
                    void print(Number n) {}
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1")).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method2")).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, cu2, null);

        VariationAnalysis result = analyzer.analyzeVariations(seq1, seq2, cu1);

        List<VaryingExpression> variations = result.varyingExpressions();
        assertEquals(1, variations.size());

        VaryingExpression var = variations.get(0);

        // Assert current behavior fails (so we know we reproduced it)
        // Or better, just assert the desired behavior and see it fail.
        assertNotNull(var.type(), "Common type should not be null");
        assertEquals("java.lang.Number", var.type().describe());
    }

    @Test
    void testCommonSupertypeGenerics() {
        String code1 = """
                import java.util.List;
                class Test {
                    void method1(List<String> list) {
                        print(list);
                    }
                    void print(Object o) {}
                }
                """;

        String code2 = """
                import java.util.List;
                class Test {
                    void method2(List<Integer> list) {
                        print(list);
                    }
                    void print(Object o) {}
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1")).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method2")).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, cu2, null);

        VariationAnalysis result = analyzer.analyzeVariations(seq1, seq2, cu1);

        List<VaryingExpression> variations = result.varyingExpressions();
        assertEquals(0, variations.size());

        // With generic mismatch, we expect fallback to Object (null) OR raw List/Collection depending on resolution
        // But definitely NOT List<Integer>
        // Since List<String> and List<Integer> are not equivalent, and our current implementation
        // returns null as common type, ASTVariationAnalyzer filters it out if it considers it "not equivalent"
        // Wait, ASTVariationAnalyzer adds it to variations ONLY if expressions are not equivalent.
        // If findCommonSupertype returns null (Object), it is still added as a variation with type=null.

        // Let's debug why size is 0.
        // Ah, because "print(list)" is structurally identical in both!
        // The variation is in the ARGUMENT TYPE, not the expression itself.
        // The expression is "list" (NameExpr) in both.
        // "list" in method1 refers to List<String>. "list" in method2 refers to List<Integer>.
        // expressionsEquivalent() checks toString(). "list".equals("list"). So it returns true.
        // So NO variation is detected for the NameExpr itself.

        // We need a test where the literal/expression itself varies but has different types.
        // e.g. passing different variables.
    }

    @Test
    void testCommonSupertypeGenericsDifferentVars() {
        String code1 = """
                import java.util.List;
                class Test {
                    void method1(List<String> list1) {
                        print(list1);
                    }
                    void print(Object o) {}
                }
                """;

        String code2 = """
                import java.util.List;
                class Test {
                    void method2(List<Integer> list2) {
                        print(list2);
                    }
                    void print(Object o) {}
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1")).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method2")).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, cu2, null);

        VariationAnalysis result = analyzer.analyzeVariations(seq1, seq2, cu1);

        List<VaryingExpression> variations = result.varyingExpressions();
        assertEquals(1, variations.size());

        VaryingExpression var = variations.get(0);

        // Should be null (Object) because List<String> is not assignable to List<Integer> and vice versa
        // And their common supertype (List<?>) is not easily represented as a single concrete type
        // without wildcards, which we might not handle yet.
        // Or it might resolve to raw List.
        // Crucially, it must NOT be List<Integer>.

        if (var.type() != null) {
             assertNotEquals("java.util.List<java.lang.Integer>", var.type().describe());
             // Ensure it is safe (e.g. java.lang.Object or raw List)
             // For now, our fix returns null if assignability check fails
        } else {
            assertNull(var.type());
        }
    }
}
