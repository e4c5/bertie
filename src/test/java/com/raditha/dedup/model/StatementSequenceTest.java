package com.raditha.dedup.model;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StatementSequenceTest {

    @Test
    void testAccessorsForMethod() {
        CompilationUnit cu = StaticJavaParser.parse("class A { void m() { int a=1; } }");
        MethodDeclaration method = cu.getClassByName("A").get().getMethods().get(0);
        Path path = Paths.get("A.java");

        StatementSequence seq = new StatementSequence(
                method.getBody().get().getStatements(),
                new Range(1, 1, 1, 1),
                0,
                method,
                cu,
                path
        );

        assertEquals("m", seq.getMethodName());
        assertTrue(seq.getCallableBody().isPresent());
        assertEquals(1, seq.size());
        assertEquals(method, seq.containingCallable());
    }

    @Test
    void testAccessorsForConstructor() {
        CompilationUnit cu = StaticJavaParser.parse("class A { A() { int a=1; } }");
        ConstructorDeclaration ctor = cu.getClassByName("A").get().getConstructors().get(0);
        Path path = Paths.get("A.java");

        StatementSequence seq = new StatementSequence(
                ctor.getBody().getStatements(),
                new Range(1, 1, 1, 1),
                0,
                ctor,
                cu,
                path
        );

        assertEquals("A", seq.getMethodName());
        assertTrue(seq.getCallableBody().isPresent());
        assertEquals(1, seq.size());
        assertEquals(ctor, seq.containingCallable());
    }

    @Test
    void testAccessorsForNullCallable() {
        StatementSequence seq = new StatementSequence(
                Collections.emptyList(),
                new Range(1, 1, 1, 1),
                0,
                null,
                null,
                null
        );

        assertEquals("unknown", seq.getMethodName());
        assertFalse(seq.getCallableBody().isPresent());
    }
}
