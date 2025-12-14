package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScopeAnalyzer.
 */
class ScopeAnalyzerTest {

    private ScopeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ScopeAnalyzer();
    }

    @Test
    void testExtractMethodParameters() {
        String code = """
            class Test {
                void method(String name, int age, boolean active) {
                    System.out.println(name);
                    System.out.println(age);
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        List<com.github.javaparser.ast.stmt.Statement> statements = method.getBody().get().getStatements();
        
        StatementSequence sequence = new StatementSequence(
            statements,
            new Range(3, 4, 1, 10),
            0,
            method,
            cu,
            Paths.get("Test.java")
        );
        
        List<ScopeAnalyzer.VariableInfo> variables = analyzer.getAvailableVariables(sequence);
        
        // Should find 3 parameters
        long paramCount = variables.stream().filter(v -> v.isParameter()).count();
        assertEquals(3, paramCount);
        
        // Check specific parameters
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("name") && v.type().equals("String")));
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("age") && v.type().equals("int")));
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("active") && v.type().equals("boolean")));
    }

    @Test
    void testExtractClassFields() {
        String code = """
                class Test {
                    private String username;
                    private int userId;
                    private final boolean isActive = true;

                    void method() {
                        System.out.println(username);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        List<com.github.javaparser.ast.stmt.Statement> statements = method.getBody().get().getStatements();

        StatementSequence sequence = new StatementSequence(
                statements,
                new Range(6, 6, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        List<ScopeAnalyzer.VariableInfo> variables = analyzer.getAvailableVariables(sequence);

        // Should find 3 fields
        long fieldCount = variables.stream().filter(v -> v.isField()).count();
        assertEquals(3, fieldCount);

        // Check specific fields
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("username") && v.isField()));
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("userId") && v.isField()));
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("isActive") && v.isFinal()));
    }

    @Test
    void testExtractLocalVariables() {
        String code = """
                class Test {
                    void method() {
                        String temp = "test";
                        int count = 5;

                        // Sequence starts here
                        System.out.println(temp);
                        System.out.println(count);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        List<com.github.javaparser.ast.stmt.Statement> allStatements = method.getBody().get().getStatements();

        // Sequence is the last 2 statements
        List<com.github.javaparser.ast.stmt.Statement> sequenceStatements = allStatements.subList(2, 4);

        StatementSequence sequence = new StatementSequence(
                sequenceStatements,
                new Range(6, 7, 1, 10),
                0,
                method,
                cu,
                Paths.get("Test.java"));

        List<ScopeAnalyzer.VariableInfo> variables = analyzer.getAvailableVariables(sequence);

        // Should find 2 local variables
        long localCount = variables.stream().filter(v -> v.isLocal()).count();
        assertTrue(localCount >= 2, "Should find at least 2 local variables, found: " + localCount);

        // Check specific locals
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("temp")));
        assertTrue(variables.stream().anyMatch(v -> v.name().equals("count")));
    }

    @Test
    void testNoMethod() {
        // Sequence with no containing method
        StatementSequence sequence = new StatementSequence(
                List.of(),
                new Range(1, 1, 1, 10),
                0,
                null, // No method
                null,
                Paths.get("Test.java"));

        List<ScopeAnalyzer.VariableInfo> variables = analyzer.getAvailableVariables(sequence);

        // Should find no variables
        assertTrue(variables.isEmpty());
    }
}
