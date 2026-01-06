package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ASTParameterExtractorTest {

    private ASTParameterExtractor extractor;
    private ASTVariationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        extractor = new ASTParameterExtractor();
        analyzer = new ASTVariationAnalyzer();
    }

    @Test
    void testExtractParametersFromVariations() {
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

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, cu2, null);

        VariationAnalysis analysis = analyzer.analyzeVariations(seq1, seq2, cu1, cu2);
        ExtractionPlan plan = extractor.extractParameters(analysis, cu1);

        // Should have parameters from varying expressions
        assertTrue(plan.parameters().size() > 0);

        // Should have created parameter for string literal
        assertTrue(plan.parameters().stream()
                .anyMatch(p -> p.getExampleValues().contains("\"Alice\"")));
    }

    @Test
    void testExtractArgumentsFromVariableReferences() {
        String code = """
                class Test {
                    void method(String userName) {
                        user.setName(userName);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration m = cu.findFirst(MethodDeclaration.class).get();

        StatementSequence seq = new StatementSequence(m.getBody().get().getStatements(), null, 0, m, cu, null);

        VariationAnalysis analysis = analyzer.analyzeVariations(seq, seq, cu, cu);
        ExtractionPlan plan = extractor.extractParameters(analysis, cu);

        // Should have arguments from variable references
        assertTrue(plan.arguments().size() > 0);

        // Should have userName as argument
        assertTrue(plan.arguments().stream()
                .anyMatch(a -> a.name().equals("userName")));
    }

    @Test
    void testMixedParametersAndArguments() {
        String code1 = """
                class Test {
                    void method1(String userName) {
                        logger.info("Processing: " + userName);
                        user.setName("Alice");
                    }
                }
                """;

        String code2 = """
                class Test {
                    void method2(String userName) {
                        logger.info("Processing: " + userName);
                        user.setName("Bob");
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration m1 = cu1.findFirst(MethodDeclaration.class).get();
        MethodDeclaration m2 = cu2.findFirst(MethodDeclaration.class).get();

        StatementSequence seq1 = new StatementSequence(m1.getBody().get().getStatements(), null, 0, m1, cu1, null);
        StatementSequence seq2 = new StatementSequence(m2.getBody().get().getStatements(), null, 0, m2, cu2, null);

        VariationAnalysis analysis = analyzer.analyzeVariations(seq1, seq2, cu1, cu2);
        ExtractionPlan plan = extractor.extractParameters(analysis, cu1);

        // Should have both parameters and arguments
        assertTrue(plan.hasParameters());
        assertTrue(plan.parameters().size() > 0);
        assertTrue(plan.arguments().size() > 0);

        // Should have userName as argument (not parameter)
        assertTrue(plan.arguments().stream()
                .anyMatch(a -> a.name().equals("userName")));

        // Should have parameter for varying literal
        assertTrue(plan.parameters().stream()
                .anyMatch(p -> p.getExampleValues().contains("\"Alice\"")));
    }
}
