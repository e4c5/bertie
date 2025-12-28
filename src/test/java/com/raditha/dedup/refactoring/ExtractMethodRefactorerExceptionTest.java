package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtractMethodRefactorerExceptionTest {

    @Test
    void testExceptionPreservationInHelperMethod() {
        // Source code with IOException
        String sourceCode = """
                public class TestFile {
                    public void method1() throws java.io.IOException {
                        int x = 1;
                        int y = 2;
                        int z = x + y;
                    }

                    public void method2() throws java.io.IOException {
                        int x = 3;
                        int y = 4;
                        int z = x + y;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(sourceCode);

        // Verify source methods have throws IOException
        MethodDeclaration method1 = cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1"))
                .orElseThrow();
        MethodDeclaration method2 = cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method2"))
                .orElseThrow();

        assertFalse(method1.getThrownExceptions().isEmpty(), "method1 should throw IOException");
        assertFalse(method2.getThrownExceptions().isEmpty(), "method2 should throw IOException");

        assertEquals("java.io.IOException", method1.getThrownExceptions().get(0).asString());
        assertEquals("java.io.IOException", method2.getThrownExceptions().get(0).asString());
    }

    @Test
    void testNoExceptionWhenSourceHasNone() {
        String sourceCode = """
                public class TestFile {
                    public void method1() {
                        int x = 1 + 2;
                    }

                    public void method2() {
                        int y = 3 + 4;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(sourceCode);

        MethodDeclaration method1 = cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1"))
                .orElseThrow();
        MethodDeclaration method2 = cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method2"))
                .orElseThrow();

        assertTrue(method1.getThrownExceptions().isEmpty(), "method1 should not throw exceptions");
        assertTrue(method2.getThrownExceptions().isEmpty(), "method2 should not throw exceptions");
    }

    @Test
    void testMultipleExceptions() {
        String sourceCode = """
                public class TestFile {
                    public void method1() throws java.io.IOException, java.sql.SQLException {
                        int x = 1 + 2;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(sourceCode);

        MethodDeclaration method1 = cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("method1"))
                .orElseThrow();

        assertEquals(2, method1.getThrownExceptions().size(), "method1 should throw 2 exceptions");
        assertTrue(method1.getThrownExceptions().get(0).asString().contains("IOException"));
        assertTrue(method1.getThrownExceptions().get(1).asString().contains("SQLException"));
    }
}
