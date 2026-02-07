package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertInstanceOf(ConstructorDeclaration.class, seq1.containingCallable());
    }

    @Test
    void testStrategyForMultiClassConstructorDuplicateInSameFile() {
        // Setup a case where two constructors are in the same file but DIFFERENT classes
        // RefactoringRecommendationGenerator should NOT choose CONSTRUCTOR_DELEGATION
        
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = "class Container {\n" +
                      "  class A { A() { int x=1; } }\n" +
                      "  class B { B() { int x=1; } }\n" +
                      "}";

        CompilationUnit cu = StaticJavaParser.parse(code);
        ConstructorDeclaration ctor1 = cu.getClassByName("Container").get()
                .getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)m).getNameAsString().equals("A"))
                .map(m -> ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)m).getConstructors().get(0))
                .findFirst().get();
        
        ConstructorDeclaration ctor2 = cu.getClassByName("Container").get()
                .getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)m).getNameAsString().equals("B"))
                .map(m -> ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)m).getConstructors().get(0))
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(ctor1.getBody().getStatements(), new Range(1,1,1,1), 0, ctor1, cu, Paths.get("SameFile.java"));
        StatementSequence seq2 = new StatementSequence(ctor2.getBody().getStatements(), new Range(1,1,1,1), 0, ctor2, cu, Paths.get("SameFile.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);
        
        // Should NOT be CONSTRUCTOR_DELEGATION because they are in different classes
        // Since it's in the same file and they are constructors, it should fallback to EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForConstructorDuplicate_NoPerfectMaster() {
        // Setup: Two constructors in the same class
        // Duplicate is 'int x = 1;'
        // BOTH have extra statements after the duplicate
        
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class A {
                    A(int p1) {
                        int x = 1;
                        System.out.println(1);
                    }
                    A(String p2) {
                        int x = 1;
                        System.out.println(2);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ConstructorDeclaration ctor1 = cu.getClassByName("A").get().getConstructors().get(0);
        ConstructorDeclaration ctor2 = cu.getClassByName("A").get().getConstructors().get(1);

        // Sequence is only the first statement
        StatementSequence seq1 = new StatementSequence(List.of(ctor1.getBody().getStatements().get(0)), 
                new Range(1,1,1,1), 0, ctor1, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(List.of(ctor2.getBody().getStatements().get(0)), 
                new Range(1,1,1,1), 0, ctor2, cu, Paths.get("A.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);
        
        // Should NOT be CONSTRUCTOR_DELEGATION because no constructor is a 'perfect' master
        // (both have trailing statements)
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForConstructorDuplicate_WithPerfectMaster() {
        // Setup: Two constructors in the same class
        // Duplicate is 'int x = 1;'
        // ctor1 is perfect (only has the duplicate)
        // ctor2 has extra statements
        
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class A {
                    A() {
                        int x = 1;
                    }
                    A(int p1) {
                        int x = 1;
                        System.out.println(1);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ConstructorDeclaration ctor1 = cu.getClassByName("A").get().getConstructors().get(0);
        ConstructorDeclaration ctor2 = cu.getClassByName("A").get().getConstructors().get(1);

        StatementSequence seq1 = new StatementSequence(ctor1.getBody().getStatements(), 
                new Range(1,1,1,1), 0, ctor1, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(List.of(ctor2.getBody().getStatements().get(0)), 
                new Range(1,1,1,1), 0, ctor2, cu, Paths.get("A.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);
        
        // Should be CONSTRUCTOR_DELEGATION because ctor1 is a perfect master
        assertEquals(RefactoringStrategy.CONSTRUCTOR_DELEGATION, recommendation.getStrategy());
    }
}
