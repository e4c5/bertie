package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.analysis.SideEffectAnalyzer;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafetyValidatorSideEffectTest {

    private SafetyValidator validator;
    private RefactoringRecommendation recommendation;

    @BeforeEach
    void setUp() {
        validator = new SafetyValidator();
        recommendation = mock(RefactoringRecommendation.class);
        when(recommendation.getSuggestedMethodName()).thenReturn("helper");
        when(recommendation.getSuggestedParameters()).thenReturn(List.of());
        when(recommendation.getStrategy()).thenReturn(RefactoringStrategy.EXTRACT_HELPER_METHOD);
    }

    static StatementSequence sequence(String methodBody) {
        String src = """
                import java.io.*;
                import java.nio.file.*;
                import java.sql.*;
                import java.util.*;
                class Fixture {
                    private java.sql.Connection connection;
                    private OrderRepository orderRepository;
                    private org.springframework.web.client.RestTemplate restTemplate;
                    private List<String> items = new ArrayList<>();
                    interface OrderRepository { void save(Object o); Object findById(long id); }
                    void m() {
                    %s
                    }
                }
                """.formatted(methodBody);
        CompilationUnit cu = StaticJavaParser.parse(src);
        MethodDeclaration m = cu.findFirst(MethodDeclaration.class, d -> d.getNameAsString().equals("m")).get();
        return new StatementSequence(m.getBody().get().getStatements(), null, 0, m, ContainerType.METHOD, cu, null);
    }

    static DuplicateCluster cluster(StatementSequence primary, StatementSequence other) {
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        SimilarityPair pair = mock(SimilarityPair.class);
        SimilarityResult similarity = mock(SimilarityResult.class);
        when(pair.seq1()).thenReturn(primary);
        when(pair.seq2()).thenReturn(other);
        when(pair.similarity()).thenReturn(similarity);
        when(similarity.variations()).thenReturn(VariationAnalysis.builder().build());
        when(cluster.primary()).thenReturn(primary);
        when(cluster.duplicates()).thenReturn(List.of(pair));
        return cluster;
    }

    @Test
    void analyzerClassifiesCommonSideEffects() {
        StatementSequence seq = sequence("""
                    System.out.println("hi");
                    Files.writeString(Path.of("x"), "data");
                    PreparedStatement ps = connection.prepareStatement("select 1");
                    ps.executeQuery();
                    orderRepository.save(this);
                    restTemplate.getForObject("http://x", String.class);
                    long now = System.currentTimeMillis();
                    UUID id = UUID.randomUUID();
                    items.add("pure");
                    int size = items.size();
                """);

        List<SideEffectAnalyzer.Category> categories = new SideEffectAnalyzer().analyze(seq).stream()
                .map(SideEffectAnalyzer.SideEffect::category).toList();

        assertEquals(List.of(
                SideEffectAnalyzer.Category.CONSOLE_IO,
                SideEffectAnalyzer.Category.FILE_IO,
                SideEffectAnalyzer.Category.DATABASE,
                SideEffectAnalyzer.Category.DATABASE,
                SideEffectAnalyzer.Category.DATABASE,
                SideEffectAnalyzer.Category.EXTERNAL_API,
                SideEffectAnalyzer.Category.NON_IDEMPOTENT,
                SideEffectAnalyzer.Category.NON_IDEMPOTENT), categories);
    }

    @Test
    void identicalSideEffectsAreAccepted() {
        StatementSequence a = sequence("""
                    int total = items.size() * 2;
                    Files.writeString(Path.of("a"), String.valueOf(total));
                """);
        StatementSequence b = sequence("""
                    int total = items.size() * 3;
                    Files.writeString(Path.of("b"), String.valueOf(total));
                """);

        SafetyValidator.ValidationResult result = validator.validate(cluster(a, b), recommendation);

        assertFalse(validator.hasDifferentSideEffects(cluster(a, b)));
        assertFalse(result.getErrors().contains(SafetyValidator.SIDE_EFFECT_ERROR), result.getErrors().toString());
    }

    @Test
    void fileWriteInOnlyOneDuplicateIsBlocking() {
        StatementSequence a = sequence("""
                    int total = items.size() * 2;
                    Files.writeString(Path.of("a"), String.valueOf(total));
                """);
        StatementSequence b = sequence("""
                    int total = items.size() * 3;
                    items.add(String.valueOf(total));
                """);

        SafetyValidator.ValidationResult result = validator.validate(cluster(a, b), recommendation);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains(SafetyValidator.SIDE_EFFECT_ERROR));
    }

    @Test
    void databaseWriteVersusReadIsBlocking() {
        StatementSequence a = sequence("""
                    Object o = items.get(0);
                    orderRepository.save(o);
                """);
        StatementSequence b = sequence("""
                    Object o = items.get(0);
                    orderRepository.findById(1L);
                """);

        assertTrue(validator.hasDifferentSideEffects(cluster(a, b)));
    }

    @Test
    void externalApiCallInOnlyOneDuplicateIsBlocking() {
        StatementSequence a = sequence("""
                    String url = "http://a";
                    String body = restTemplate.getForObject(url, String.class);
                """);
        StatementSequence b = sequence("""
                    String url = "http://b";
                    String body = url.toUpperCase();
                """);

        assertTrue(validator.hasDifferentSideEffects(cluster(a, b)));
    }

    @Test
    void nonIdempotentCallInOnlyOneDuplicateIsBlocking() {
        StatementSequence a = sequence("""
                    long seed = System.nanoTime();
                    items.add(String.valueOf(seed));
                """);
        StatementSequence b = sequence("""
                    long seed = 42L;
                    items.add(String.valueOf(seed));
                """);

        assertTrue(validator.hasDifferentSideEffects(cluster(a, b)));
    }

    @Test
    void pureDuplicatesWithDifferentLiteralsHaveNoSideEffectIssue() {
        StatementSequence a = sequence("""
                    String s = "a".trim();
                    items.add(s);
                """);
        StatementSequence b = sequence("""
                    String s = "b".trim();
                    items.add(s);
                """);

        assertFalse(validator.hasDifferentSideEffects(cluster(a, b)));
    }
}
