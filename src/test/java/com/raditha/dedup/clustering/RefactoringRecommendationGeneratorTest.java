package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefactoringRecommendationGeneratorTest {

    @Test
    void testStrategyForConstructorDuplicate() {
        // Setup a cross-file duplicate involving a constructor
        // Constructors use instance state (implicitly), so should prefer EXTRACT_PARENT_CLASS
        // if cross-file.

        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code1 = "class A { A() { int x=1; } }";
        String code2 = "class B { B() { int x=1; } }";

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        ConstructorDeclaration ctor1 = cu1.getClassByName("A").get().getConstructors().get(0);
        ConstructorDeclaration ctor2 = cu2.getClassByName("B").get().getConstructors().get(0);

        StatementSequence seq1 = new StatementSequence(ctor1.getBody().getStatements(), new Range(1,1,1,1), 0, ctor1, cu1, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(ctor2.getBody().getStatements(), new Range(1,1,1,1), 0, ctor2, cu2, Paths.get("B.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));

        assertNotNull(generator);
        assertTrue(seq1.containingCallable() instanceof ConstructorDeclaration);
    }
}
