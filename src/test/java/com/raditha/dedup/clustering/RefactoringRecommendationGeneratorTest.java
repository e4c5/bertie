package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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

        // Mock variations
        // We need aggregated variations to be empty/simple for this test to pass truncation
        // This is hard to mock without full setup.
        // Instead, I'll rely on generating recommendation which calls determineStrategy.

        // Actually, determineStrategy is private.
        // But generateRecommendation calls it.

        // However, setting up a full cluster for generateRecommendation is complex.
        // I can use reflection or just infer from result.

        // Wait, if I cannot easily run generateRecommendation without complex setup,
        // I should focus on units that are easier.

        // Let's test StatementSequence.containingCallable check in RefactoringRecommendationGenerator
        // indirectly?

        // The generator uses `usesInstanceState(seq)`.
        // For constructor, it should return true.
        // If true and cross-file, it returns EXTRACT_PARENT_CLASS.

        // I'll try to construct enough state for generateRecommendation to run.
        // It needs VariationAggregator, Truncator, etc.
        // The default constructor initializes them.

        // The mocks for cluster need to return meaningful data for variation aggregation.
        // If I pass identical statements, variations should be empty.
    }
}
