package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.junit.jupiter.api.BeforeAll;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DuplicationAnalyzerOverlappingTest {

    private final DuplicationAnalyzer analyzer = new DuplicationAnalyzer();

    @BeforeAll
    static void setUpClass() throws IOException {
        java.io.File configFile = new java.io.File("src/test/resources/analyzer-tests.yml");
        Settings.loadConfigMap(configFile);
    }

    @Test
    void testDifferentFiles() {
        StatementSequence s1 = createSequence(Paths.get("A.java"), 1, 10, null);
        StatementSequence s2 = createSequence(Paths.get("B.java"), 1, 10, null);
        assertFalse(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testSameMethodOverlapping() {
        CallableDeclaration<?> method = mock(CallableDeclaration.class);
        Path path = Paths.get("A.java");
        StatementSequence s1 = createSequence(path, 1, 10, method);
        StatementSequence s2 = createSequence(path, 5, 15, method);
        assertTrue(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testSameMethodNotOverlapping() {
        CallableDeclaration<?> method = mock(CallableDeclaration.class);
        Path path = Paths.get("A.java");
        StatementSequence s1 = createSequence(path, 1, 10, method);
        StatementSequence s2 = createSequence(path, 11, 20, method);
        assertFalse(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testDifferentMethodsSameFile() {
        CallableDeclaration<?> m1 = mock(CallableDeclaration.class);
        CallableDeclaration<?> m2 = mock(CallableDeclaration.class);
        Path path = Paths.get("A.java");
        // even if ranges "overlap" in lines (unlikely in real code but physically possible if we mock)
        // the current logic return m1.equals(m2) && rangesOverlap
        StatementSequence s1 = createSequence(path, 1, 10, m1);
        StatementSequence s2 = createSequence(path, 5, 15, m2); 
        assertFalse(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testNullMethodsSameFileOverlapping() {
        Path path = Paths.get("A.java");
        StatementSequence s1 = createSequence(path, 1, 10, null);
        StatementSequence s2 = createSequence(path, 5, 15, null);
        assertTrue(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testNullMethodsSameFileNotOverlapping() {
        Path path = Paths.get("A.java");
        StatementSequence s1 = createSequence(path, 1, 10, null);
        StatementSequence s2 = createSequence(path, 11, 20, null);
        assertFalse(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    @Test
    void testNullPaths() {
        StatementSequence s1 = createSequence(null, 1, 10, null);
        StatementSequence s2 = createSequence(null, 1, 10, null);
        // Current logic: if s1.path or s2.path is null, it skips the first block
        // and skips the second block (361), then returns false (365)
        assertFalse(analyzer.isPhysicallyOverlapping(s1, s2));
    }
    
    @Test
    void testMixedNullMethodsOverlapping() {
        CallableDeclaration<?> m1 = mock(CallableDeclaration.class);
        Path path = Paths.get("A.java");
        StatementSequence s1 = createSequence(path, 1, 10, m1);
        StatementSequence s2 = createSequence(path, 5, 15, null);
        // m1 != null, m2 == null -> fails 354
        // s1.path == s2.path && rangesOverlap -> returns true (362)
        assertTrue(analyzer.isPhysicallyOverlapping(s1, s2));
    }

    private StatementSequence createSequence(Path path, int start, int end, CallableDeclaration<?> method) {
        return new StatementSequence(
                Collections.emptyList(),
                new Range(start, end, 1, 1),
                0,
                method,
                null,
                path
        );
    }
}
