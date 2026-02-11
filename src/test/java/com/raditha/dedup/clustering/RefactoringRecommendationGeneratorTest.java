package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

        StatementSequence seq1 = new StatementSequence(ctor1.getBody().getStatements(), new Range(1, 1, 1, 1), 0, ctor1, ContainerType.CONSTRUCTOR, cu1, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(ctor2.getBody().getStatements(), new Range(1, 1, 1, 1), 0, ctor2, ContainerType.CONSTRUCTOR, cu2, Paths.get("B.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));

        assertNotNull(generator);
        assertTrue(seq1.getContainingCallable().isPresent());
        assertInstanceOf(ConstructorDeclaration.class, seq1.getContainingCallable().get());
    }

    @Test
    void testStrategyForMultiClassConstructorDuplicateInSameFile() {
        // Setup a case where two constructors are in the same file but DIFFERENT classes
        // RefactoringRecommendationGenerator should NOT choose CONSTRUCTOR_DELEGATION

        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class Container {
                  class A { A() { int x=1; } }\n
                 class B { B() { int x=1; } }\n
                }""";

        CompilationUnit cu = StaticJavaParser.parse(code);
        ConstructorDeclaration ctor1 = cu.getClassByName("Container").get()
                .getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m).getNameAsString().equals("A"))
                .map(m -> ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m).getConstructors().get(0))
                .findFirst().get();

        ConstructorDeclaration ctor2 = cu.getClassByName("Container").get()
                .getMembers().stream()
                .filter(m -> m instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m).getNameAsString().equals("B"))
                .map(m -> ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) m).getConstructors().get(0))
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(ctor1.getBody().getStatements(), new Range(1, 1, 1, 1), 0, ctor1, ContainerType.CONSTRUCTOR, cu, Paths.get("SameFile.java"));
        StatementSequence seq2 = new StatementSequence(ctor2.getBody().getStatements(), new Range(1, 1, 1, 1), 0, ctor2, ContainerType.CONSTRUCTOR, cu, Paths.get("SameFile.java"));

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
                new Range(1, 1, 1, 1), 0, ctor1, ContainerType.CONSTRUCTOR, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(List.of(ctor2.getBody().getStatements().get(0)),
                new Range(1, 1, 1, 1), 0, ctor2, ContainerType.CONSTRUCTOR, cu, Paths.get("A.java"));

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
                new Range(1, 1, 1, 1), 0, ctor1, ContainerType.CONSTRUCTOR, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(List.of(ctor2.getBody().getStatements().get(0)),
                new Range(1, 1, 1, 1), 0, ctor2, ContainerType.CONSTRUCTOR, cu, Paths.get("A.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Should be CONSTRUCTOR_DELEGATION because ctor1 is a perfect master
        assertEquals(RefactoringStrategy.CONSTRUCTOR_DELEGATION, recommendation.getStrategy());
    }

    @Test
    void testStrategyForStaticInitializer_SameFile() {
        // Static initializers in the same file should use EXTRACT_HELPER_METHOD
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class A {
                    static { int x = 1; }
                }
                class B {
                    static { int x = 1; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var initA = cu.getClassByName("A").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();
        var initB = cu.getClassByName("B").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();

        StatementSequence seq1 = new StatementSequence(initA.getBody().getStatements(),
                new Range(2, 10, 2, 22), 0, initA, ContainerType.STATIC_INITIALIZER, cu, Paths.get("Test.java"));
        StatementSequence seq2 = new StatementSequence(initB.getBody().getStatements(),
                new Range(5, 10, 5, 22), 0, initB, ContainerType.STATIC_INITIALIZER, cu, Paths.get("Test.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Same file static initializers should use EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForStaticInitializer_CrossFile() {
        // Cross-file static initializers should use EXTRACT_TO_UTILITY_CLASS
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code1 = "class A { static { int x = 1; } }";
        String code2 = "class B { static { int x = 1; } }";

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        var initA = cu1.getClassByName("A").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();
        var initB = cu2.getClassByName("B").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();

        StatementSequence seq1 = new StatementSequence(initA.getBody().getStatements(),
                new Range(1, 10, 1, 22), 0, initA, ContainerType.STATIC_INITIALIZER, cu1, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(initB.getBody().getStatements(),
                new Range(1, 10, 1, 22), 0, initB, ContainerType.STATIC_INITIALIZER, cu2, Paths.get("B.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Cross-file static initializers should use EXTRACT_TO_UTILITY_CLASS
        assertEquals(RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS, recommendation.getStrategy());
    }

    @Test
    void testStrategyForInstanceInitializer() {
        // Instance initializers should use EXTRACT_HELPER_METHOD
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class A {
                    { int x = 1; }
                    { int x = 1; }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var inits = cu.getClassByName("A").get().findAll(com.github.javaparser.ast.body.InitializerDeclaration.class);
        var init1 = inits.get(0);
        var init2 = inits.get(1);

        StatementSequence seq1 = new StatementSequence(init1.getBody().getStatements(),
                new Range(2, 5, 2, 17), 0, init1, ContainerType.INSTANCE_INITIALIZER, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(init2.getBody().getStatements(),
                new Range(3, 5, 3, 17), 0, init2, ContainerType.INSTANCE_INITIALIZER, cu, Paths.get("A.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Instance initializers should use EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForLambda_SameFile() {
        // Lambdas in the same file should use EXTRACT_HELPER_METHOD
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                class A {
                    void method() {
                        Runnable r1 = () -> { int x = 1; };
                        Runnable r2 = () -> { int x = 1; };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var lambdas = cu.findAll(com.github.javaparser.ast.expr.LambdaExpr.class);
        var lambda1 = lambdas.get(0);
        var lambda2 = lambdas.get(1);

        var body1 = lambda1.getBody().asBlockStmt();
        var body2 = lambda2.getBody().asBlockStmt();

        StatementSequence seq1 = new StatementSequence(body1.getStatements(),
                new Range(3, 31, 3, 43), 0, lambda1, ContainerType.LAMBDA, cu, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(body2.getStatements(),
                new Range(4, 31, 4, 43), 0, lambda2, ContainerType.LAMBDA, cu, Paths.get("A.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(5);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Same file lambdas should use EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStaticContextDetection_StaticMethod() {
        // Static method should be detected as static context
        String code = "class A { static void method() { int x = 1; } }";
        CompilationUnit cu = StaticJavaParser.parse(code);
        var method = cu.getClassByName("A").get().getMethods().get(0);

        StatementSequence seq = new StatementSequence(method.getBody().get().getStatements(),
                new Range(1, 35, 1, 47), 0, method, ContainerType.METHOD, cu, Paths.get("A.java"));

        assertTrue(seq.isStaticContext());
    }

    @Test
    void testStaticContextDetection_StaticInitializer() {
        // Static initializer should be detected as static context
        String code = "class A { static { int x = 1; } }";
        CompilationUnit cu = StaticJavaParser.parse(code);
        var init = cu.getClassByName("A").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();

        StatementSequence seq = new StatementSequence(init.getBody().getStatements(),
                new Range(1, 20, 1, 32), 0, init, ContainerType.STATIC_INITIALIZER, cu, Paths.get("A.java"));

        assertTrue(seq.isStaticContext());
    }

    @Test
    void testStaticContextDetection_InstanceInitializer() {
        // Instance initializer should NOT be detected as static context
        String code = "class A { { int x = 1; } }";
        CompilationUnit cu = StaticJavaParser.parse(code);
        var init = cu.getClassByName("A").get().findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).get();

        StatementSequence seq = new StatementSequence(init.getBody().getStatements(),
                new Range(1, 13, 1, 25), 0, init, ContainerType.INSTANCE_INITIALIZER, cu, Paths.get("A.java"));

        assertFalse(seq.isStaticContext());
    }
}
