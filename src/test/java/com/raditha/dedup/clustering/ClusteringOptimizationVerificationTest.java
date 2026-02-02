package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClusteringOptimizationVerificationTest {

    private ReturnTypeResolver returnTypeResolver;

    @BeforeAll
    static void setupClass() throws java.io.IOException {
        // Load test configuration pointing to test-bed
        java.io.File configFile = new java.io.File("src/test/resources/analyzer-tests.yml");
        sa.com.cloudsolutions.antikythera.configuration.Settings.loadConfigMap(configFile);
        
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
    }

    @BeforeEach
    void setUp() {
        returnTypeResolver = new ReturnTypeResolver();
    }

    @Test
    void testReturnTypeResolver_findTypeInContext() {
        String code = """
                class Test {
                    String field;
                    void method(int param) {
                        double local = 1.0;
                        java.util.List.of(1).forEach(n -> {
                            boolean inLambda = true;
                        });
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
        List<Statement> stmts = method.getBody().get().getStatements();
        StatementSequence sequence = new StatementSequence(stmts, null, 0, method, cu, Paths.get("Test.java"));

        assertEquals("double", returnTypeResolver.findTypeInContext(sequence, "local").asString());
        assertEquals("int", returnTypeResolver.findTypeInContext(sequence, "param").asString());
        assertEquals("String", returnTypeResolver.findTypeInContext(sequence, "field").asString());
        assertEquals("boolean", returnTypeResolver.findTypeInContext(sequence, "inLambda").asString());
    }

    @Test
    void testReturnTypeResolver_inferTypeFromExpression() {
        // This is private but tested via public methods or indirectly. 
        // Let's test determineReturnType with a return statement.
        String code = """
                class Test {
                    String method() {
                        return "hello";
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
        List<Statement> stmts = method.getBody().get().getStatements();
        StatementSequence sequence = new StatementSequence(stmts, null, 0, method, cu, Paths.get("Test.java"));
        
        // We need a cluster for determineReturnType, but we can test internal methods if we make them public or use findTypeInContext
        // Let's use findTypeInContext for a variable assigned a literal
        String code2 = """
                class Test {
                    void method() {
                        var x = "test";
                    }
                }
                """;
        CompilationUnit cu2 = StaticJavaParser.parse(code2);
        MethodDeclaration method2 = cu2.findFirst(MethodDeclaration.class).get();
        StatementSequence sequence2 = new StatementSequence(method2.getBody().get().getStatements(), null, 0, method2, cu2, Paths.get("Test.java"));
        
        // findTypeInContext should handle 'var' by looking at initializer
        assertEquals("String", returnTypeResolver.findTypeInContext(sequence2, "x").asString());
    }
}
