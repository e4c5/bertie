package com.raditha.dedup.clustering;

import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DuplicateClusterer.
 */
class DuplicateClustererTest {

    private DuplicateClusterer clusterer;

    @BeforeEach
    void setUp() {
        clusterer = new DuplicateClusterer();
    }

    @Test
    void testEmptyPairs() {
        List<DuplicateCluster> clusters = clusterer.cluster(List.of());
        assertTrue(clusters.isEmpty());
    }

    @Test
    void testSinglePair() {
        SimilarityPair pair = createPair(10, 20, 0.90);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair));

        assertEquals(1, clusters.size());
        DuplicateCluster cluster = clusters.get(0);

        // Primary should be the earlier sequence (line 10)
        assertEquals(10, cluster.primary().range().startLine());
        assertEquals(1, cluster.duplicates().size());
    }

    @Test
    void testMultiplePairsSamePrimary() {
        // 3 duplicates of the same code at line 10
        SimilarityPair pair1 = createPair(10, 20, 0.95);
        SimilarityPair pair2 = createPair(10, 30, 0.92);
        SimilarityPair pair3 = createPair(10, 40, 0.90);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair1, pair2, pair3));

        assertEquals(1, clusters.size());
        DuplicateCluster cluster = clusters.get(0);

        assertEquals(10, cluster.primary().range().startLine());
        assertEquals(3, cluster.duplicates().size());
        assertTrue(cluster.estimatedLOCReduction() > 0);
    }

    @Test
    void testMultipleClusters() {
        // Two separate duplicate groups
        SimilarityPair pair1 = createPair(10, 20, 0.95); // Group 1
        SimilarityPair pair2 = createPair(10, 25, 0.92); // Group 1
        SimilarityPair pair3 = createPair(50, 60, 0.90); // Group 2

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair1, pair2, pair3));

        assertEquals(2, clusters.size());
    }

    @Test
    void testClustersSortedByLOCReduction() {
        // Create pairs with different LOC potentials
        SimilarityPair smallPair = createPairWithSize(10, 20, 0.95, 3);
        SimilarityPair largePair = createPairWithSize(50, 60, 0.90, 10);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(smallPair, largePair));

        // Should be sorted by LOC reduction (largest first)
        assertTrue(clusters.get(0).estimatedLOCReduction() >= clusters.get(1).estimatedLOCReduction());
    }

    @Test
    void testSimilarityThresholdFiltering() {
        // Low similarity pair should be filtered out
        DuplicateClusterer strictClusterer = new DuplicateClusterer(0.95);

        SimilarityPair lowSimilarity = createPair(10, 20, 0.80); // Below threshold
        SimilarityPair highSimilarity = createPair(30, 40, 0.98); // Above threshold

        List<DuplicateCluster> clusters = strictClusterer.cluster(
                List.of(lowSimilarity, highSimilarity));

        // Should only cluster the high similarity pair
        assertEquals(1, clusters.size());
        assertEquals(30, clusters.get(0).primary().range().startLine());
    }

    @Test
    void testLOCCalculationAccuracy() {
        // Verify LOC calculation: 3 duplicates of 5 lines each
        // Reduction = 15 (duplicate lines) - 3 (call sites) - 1 (method overhead) = 11
        SimilarityPair pair1 = createPairWithSize(10, 20, 0.95, 5);
        SimilarityPair pair2 = createPairWithSize(10, 30, 0.95, 5);
        SimilarityPair pair3 = createPairWithSize(10, 40, 0.95, 5);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair1, pair2, pair3));

        assertEquals(1, clusters.size());
        // 3 duplicates × 5 lines = 15 lines
        // - 3 call sites - 1 method overhead = 11 LOC reduction
        assertEquals(11, clusters.get(0).estimatedLOCReduction());
    }

    // Helper methods

    private SimilarityPair createPair(int startLine1, int startLine2, double similarity) {
        return createPairWithSize(startLine1, startLine2, similarity, 5);
    }

    private SimilarityPair createPairWithSize(int startLine1, int startLine2, double similarity, int size) {
        StatementSequence seq1 = createSequence(startLine1, size);
        StatementSequence seq2 = createSequence(startLine2, size);

        SimilarityResult result = new SimilarityResult(
                similarity, // overallScore
                similarity, // lcsScore
                similarity, // levenshteinScore
                similarity, // structuralScore
                0, // tokens1Count
                0, // tokens2Count
                new VariationAnalysis(List.of(), false),
                new TypeCompatibility(true, java.util.Map.of(), null, List.of()),
                false // canRefactor
        );

        return new SimilarityPair(seq1, seq2, result);
    }

    private StatementSequence createSequence(int startLine, int size) {
        List<com.github.javaparser.ast.stmt.Statement> statements = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            statements.add(new com.github.javaparser.ast.stmt.EmptyStmt());
        }

        return new StatementSequence(
                statements,
                new Range(startLine, startLine + size - 1, 1, 10),
                0,
                null,
                null,
                Paths.get("Test.java"));
    }
}
