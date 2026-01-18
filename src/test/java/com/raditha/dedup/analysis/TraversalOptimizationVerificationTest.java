package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TraversalOptimizationVerificationTest {

    private DataFlowAnalyzer dataFlowAnalyzer;
    private ASTVariationAnalyzer astVariationAnalyzer;

    @BeforeEach
    void setUp() {
        dataFlowAnalyzer = new DataFlowAnalyzer();
        astVariationAnalyzer = new ASTVariationAnalyzer();
    }

    @Test
    void testDataFlowAnalyzer_DefinedVariables() {
        String code = """
                class Test {
                    void method() {
                        int x = 1;
                        if (true) {
                            int y = 2;
                        }
                        try {
                            int z = 3;
                        } catch (Exception e) {
                            String msg = e.getMessage();
                        }
                        java.util.List.of(1, 2, 3).forEach(n -> {
                            int internal = n * 2;
                        });
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
        List<Statement> stmts = method.getBody().get().getStatements();
        StatementSequence sequence = new StatementSequence(stmts, null, 0, method, cu, Paths.get("Test.java"));

        Set<String> defined = dataFlowAnalyzer.findDefinedVariables(sequence);

        // Top level
        assertTrue(defined.contains("x"), "Should find x");
        // Nested in if
        assertTrue(defined.contains("y"), "Should find y");
        // Nested in try
        assertTrue(defined.contains("z"), "Should find z");
        // Catch parameter
        assertTrue(defined.contains("e"), "Should find e");
        // Nested in catch
        assertTrue(defined.contains("msg"), "Should find msg");
        // Lambda parameter
        assertTrue(defined.contains("n"), "Should find n");
        // Nested in lambda
        assertTrue(defined.contains("internal"), "Should find internal");
    }

    @Test
    void testDataFlowAnalyzer_LiveOutWithNestedScope() {
        String code = """
                class Test {
                    void method() {
                        int outer = getVal();
                        if (true) {
                            int inner = getVal();
                        }
                        try {
                            int catchMe = getVal();
                        } catch (Exception e) {
                            int caught = getVal();
                        }
                        System.out.println(outer);
                    }
                    int getVal() { return 1; }
                }
                """;
        // Let's analyze the sequence from 'int outer = 1' up to before 'System.out.println(outer)'
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
        BlockStmt body = method.getBody().get();
        List<Statement> stmts = body.getStatements().subList(0, body.getStatements().size() - 1);
        
        // We need accurate range for findLiveOutVariables to work (it checks if usage is AFTER)
        // StaticJavaParser usually provides ranges.
        Statement lastStmtInSeq = stmts.get(stmts.size() - 1);
        int endLine = lastStmtInSeq.getRange().get().end.line;
        int endCol = lastStmtInSeq.getRange().get().end.column;

        StatementSequence sequence = new StatementSequence(stmts, 
            new com.raditha.dedup.model.Range(1, endLine, 1, endCol), 
            0, method, cu, Paths.get("Test.java"));

        Set<String> liveOut = dataFlowAnalyzer.findLiveOutVariables(sequence);

        // 'outer' should be live out
        assertTrue(liveOut.contains("outer"), "outer should be live out");
        
        // 'inner', 'catchMe', 'e', 'caught' should NOT be live out because they are internal to the sequence scope
        assertFalse(liveOut.contains("inner"), "inner should NOT be live out (internal scope)");
        assertFalse(liveOut.contains("catchMe"), "catchMe should NOT be live out (internal scope)");
        assertFalse(liveOut.contains("e"), "e should NOT be live out (internal scope)");
        assertFalse(liveOut.contains("caught"), "caught should NOT be live out (internal scope)");
    }

    @Test
    void testASTVariationAnalyzer_findDeclarations() {
        String code = """
                class Test {
                    void method() {
                        int a = 1;
                        java.util.List.of(1).forEach(p -> {
                           int b = 2;
                        });
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
        List<Statement> stmts = method.getBody().get().getStatements();
        StatementSequence sequence = new StatementSequence(stmts, null, 0, method, cu, Paths.get("Test.java"));

        // VariationAnalysis includes declaredInternalVariables which is populated by findDeclarations
        VariationAnalysis result = astVariationAnalyzer.analyzeVariations(sequence, sequence, cu);
        Set<String> declared = result.getDeclaredInternalVariables();

        assertTrue(declared.contains("a"), "Should find a");
        assertTrue(declared.contains("p"), "Should find lambda parameter p");
        assertTrue(declared.contains("b"), "Should find b inside lambda");
    }
}
