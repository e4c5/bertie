package com.raditha.dedup.model;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static com.raditha.dedup.model.ContainerType.*;

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
                METHOD,
                cu,
                path
        );

        assertEquals("m", seq.getMethodName());
        assertTrue(seq.getCallableBody().isPresent());
        assertEquals(1, seq.size());
        assertTrue(seq.getContainingCallable().isPresent());
        assertEquals(method, seq.getContainingCallable().get());
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
                CONSTRUCTOR,
                cu,
                path
        );

        assertEquals("A", seq.getMethodName());
        assertTrue(seq.getCallableBody().isPresent());
        assertEquals(1, seq.size());
        assertTrue(seq.getContainingCallable().isPresent());
        assertEquals(ctor, seq.getContainingCallable().get());
    }

    @Test
    void testAccessorsForNullCallable() {
        StatementSequence seq = new StatementSequence(
                Collections.emptyList(),
                new Range(1, 1, 1, 1),
                0,
                null,
                null,
                null,
                null
        );

        assertEquals("unknown", seq.getMethodName());
        assertFalse(seq.getCallableBody().isPresent());
    }
}
