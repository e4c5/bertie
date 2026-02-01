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
        // Current behavior: commonType is null (Object)
        // Desired behavior: commonType is Number

        // Assert current behavior fails (so we know we reproduced it)
        // Or better, just assert the desired behavior and see it fail.
        assertNotNull(var.type(), "Common type should not be null");
        assertEquals("java.lang.Number", var.type().describe());
    }
}
