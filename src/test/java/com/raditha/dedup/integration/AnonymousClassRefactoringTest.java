package com.raditha.dedup.integration;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.raditha.dedup.clustering.RefactoringRecommendationGenerator;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for anonymous class duplicate detection and strategy selection.
 */
class AnonymousClassRefactoringTest {

    @Test
    void testStrategyForAnonymousClassDuplicates_SameInterface() {
        // Two anonymous classes implementing the same interface with identical method bodies
        // should be eligible for EXTRACT_NAMED_INNER_CLASS
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                interface Processor { void process(String input); }
                class Container {
                    void setup() {
                        Processor p1 = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                            }
                        };
                        Processor p2 = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var anonymousClasses = cu.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .toList();

        assertEquals(2, anonymousClasses.size());

        // Get the process() methods from each anonymous class
        MethodDeclaration method1 = anonymousClasses.get(0).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();
        MethodDeclaration method2 = anonymousClasses.get(1).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements(),
                new Range(6, 1, 8, 1), 0, method1,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));
        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements(),
                new Range(13, 1, 15, 1), 0, method2,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(4);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        assertEquals(RefactoringStrategy.EXTRACT_NAMED_INNER_CLASS, recommendation.getStrategy());
    }

    @Test
    void testStrategyForAnonymousClassDuplicates_DifferentInterfaces() {
        // Two anonymous classes implementing different interfaces should fall back to EXTRACT_HELPER_METHOD
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                interface Processor { void process(String input); }
                interface Handler { void process(String input); }
                class Container {
                    void setup() {
                        Processor p1 = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                            }
                        };
                        Handler h1 = new Handler() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var anonymousClasses = cu.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .toList();

        assertEquals(2, anonymousClasses.size());

        MethodDeclaration method1 = anonymousClasses.get(0).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();
        MethodDeclaration method2 = anonymousClasses.get(1).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements(),
                new Range(7, 1, 9, 1), 0, method1,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));
        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements(),
                new Range(14, 1, 16, 1), 0, method2,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(4);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Different interfaces -> fall back to EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForAnonymousClass_PartialBodyDuplicate() {
        // Anonymous class where only part of the body is duplicated (not full method body)
        // should use EXTRACT_HELPER_METHOD, not EXTRACT_NAMED_INNER_CLASS
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                interface Processor { void process(String input); }
                class Container {
                    void setup() {
                        Processor p1 = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                                System.out.println("extra line in p1");
                            }
                        };
                        Processor p2 = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                                System.out.println("extra line in p2");
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        var anonymousClasses = cu.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .toList();

        MethodDeclaration method1 = anonymousClasses.get(0).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();
        MethodDeclaration method2 = anonymousClasses.get(1).getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();

        // Only the first 2 statements are duplicated (not the full body of 3 statements)
        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements().subList(0, 2),
                new Range(7, 1, 8, 1), 0, method1,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));
        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements().subList(0, 2),
                new Range(14, 1, 15, 1), 0, method2,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(2);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Partial body -> fall back to EXTRACT_HELPER_METHOD
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, recommendation.getStrategy());
    }

    @Test
    void testStrategyForAnonymousClass_CrossFile() {
        // Cross-file anonymous class duplicates should be MANUAL_REVIEW_REQUIRED
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code1 = """
                interface Processor { void process(String input); }
                class A {
                    void setup() {
                        Processor p = new Processor() {
                            @Override
                            public void process(String input) {
                                System.out.println(input.trim());
                            }
                        };
                    }
                }
                """;
        String code2 = """
                interface Processor { void process(String input); }
                class B {
                    void setup() {
                        Processor p = new Processor() {
                            @Override
                            public void process(String input) {
                                System.out.println(input.trim());
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        MethodDeclaration method1 = cu1.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .findFirst().get().getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();
        MethodDeclaration method2 = cu2.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .findFirst().get().getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements(),
                new Range(7, 1, 7, 50), 0, method1,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu1, Paths.get("A.java"));
        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements(),
                new Range(7, 1, 7, 50), 0, method2,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu2, Paths.get("B.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(2);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        assertEquals(RefactoringStrategy.MANUAL_REVIEW_REQUIRED, recommendation.getStrategy());
    }

    @Test
    void testStrategyForMixedContainerTypes() {
        // If one sequence is ANONYMOUS_CLASS_METHOD and another is METHOD,
        // should not qualify for EXTRACT_NAMED_INNER_CLASS
        RefactoringRecommendationGenerator generator = new RefactoringRecommendationGenerator();

        String code = """
                interface Processor { void process(String input); }
                class Container {
                    void regularMethod(String input) {
                        String trimmed = input.trim();
                        System.out.println(trimmed);
                    }
                    void setup() {
                        Processor p = new Processor() {
                            @Override
                            public void process(String input) {
                                String trimmed = input.trim();
                                System.out.println(trimmed);
                            }
                        };
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);

        MethodDeclaration regularMethod = cu.getClassByName("Container").get().getMethodsByName("regularMethod").get(0);
        MethodDeclaration anonymousMethod = cu.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .findFirst().get().getAnonymousClassBody().get().stream()
                .filter(m -> m instanceof MethodDeclaration)
                .map(m -> (MethodDeclaration) m)
                .findFirst().get();

        StatementSequence seq1 = new StatementSequence(
                regularMethod.getBody().get().getStatements(),
                new Range(3, 1, 5, 1), 0, regularMethod,
                ContainerType.METHOD, cu, Paths.get("Container.java"));
        StatementSequence seq2 = new StatementSequence(
                anonymousMethod.getBody().get().getStatements(),
                new Range(10, 1, 12, 1), 0, anonymousMethod,
                ContainerType.ANONYMOUS_CLASS_METHOD, cu, Paths.get("Container.java"));

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.estimatedLOCReduction()).thenReturn(4);

        RefactoringRecommendation recommendation = generator.generateRecommendation(cluster);

        // Mixed types -> should not use EXTRACT_NAMED_INNER_CLASS
        // The primary is METHOD so normal logic applies
        assertNotEquals(RefactoringStrategy.EXTRACT_NAMED_INNER_CLASS, recommendation.getStrategy());
    }
}
