package com.raditha.dedup.clustering;

import com.raditha.dedup.model.*;

import java.util.*;

/**
 * Clusters duplicate detection results by grouping related duplicates together.
 * Groups similarity pairs by their primary sequence (earliest occurrence) and
 * calculates potential LOC reduction for each cluster.
 */
public class DuplicateClusterer {

    private final double similarityThreshold;

    /**
     * Create clusterer with default 75% similarity threshold.
     */
    public DuplicateClusterer() {
        this(0.75);
    }

    /**
     * Create clusterer with custom similarity threshold.
     * Only pairs meeting this threshold will be clustered.
     * 
     * @param similarityThreshold Minimum similarity (0.0-1.0) to include in
     *                            clusters
     */
    public DuplicateClusterer(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Cluster similarity pairs into groups.
     * Filters pairs by similarity threshold, then groups by primary sequence.
     * 
     * @param pairs List of duplicate pairs to cluster
     * @return List of clusters sorted by LOC reduction potential (highest first)
     */
    public List<DuplicateCluster> cluster(List<SimilarityPair> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }

        // Filter by similarity threshold
        List<SimilarityPair> filtered = pairs.stream()
                .filter(p -> p.similarity().overallScore() >= similarityThreshold)
                .toList();

        if (filtered.isEmpty()) {
            return List.of();
        }

        // Group pairs by primary sequence
        Map<StatementSequence, List<SimilarityPair>> groups = groupByPrimary(filtered);

        // Convert to clusters
        List<DuplicateCluster> clusters = new ArrayList<>();
        for (Map.Entry<StatementSequence, List<SimilarityPair>> entry : groups.entrySet()) {
            StatementSequence primary = entry.getKey();
            List<SimilarityPair> groupPairs = entry.getValue();

            // Calculate LOC reduction potential
            // For each pair, get the duplicate (non-primary) sequence
            int totalDuplicateLines = groupPairs.stream()
                    .mapToInt(p -> {
                        StatementSequence duplicate = p.seq1().equals(primary) ? p.seq2() : p.seq1();
                        return duplicate.statements().size();
                    })
                    .sum();

            // LOC reduction = duplicate lines - method call overhead
            // Each duplicate becomes a single method call
            // Plus 1 line for the extracted method signature
            int callSiteLines = groupPairs.size();
            int methodOverhead = 1;
            int locReduction = Math.max(0, totalDuplicateLines - callSiteLines - methodOverhead);

            DuplicateCluster cluster = new DuplicateCluster(
                    primary,
                    groupPairs,
                    null, // Recommendation added by RefactoringRecommendationGenerator
                    locReduction);

            clusters.add(cluster);
        }

        // Sort by LOC reduction potential (highest first)
        return clusters.stream()
                .sorted((a, b) -> Integer.compare(b.estimatedLOCReduction(), a.estimatedLOCReduction()))
                .toList();
    }

    /**
     * Group pairs by their primary (earliest) sequence.
     */
    private Map<StatementSequence, List<SimilarityPair>> groupByPrimary(List<SimilarityPair> pairs) {
        Map<StatementSequence, List<SimilarityPair>> groups = new HashMap<>();

        for (SimilarityPair pair : pairs) {
            StatementSequence primary = getPrimary(pair);
            groups.computeIfAbsent(primary, k -> new ArrayList<>()).add(pair);
        }

        return groups;
    }

    /**
     * Get the primary sequence from a pair.
     * Primary is defined as the sequence that appears first in the file (lowest
     * line number).
     */
    private StatementSequence getPrimary(SimilarityPair pair) {
        int line1 = pair.seq1().range().startLine();
        int line2 = pair.seq2().range().startLine();
        return line1 < line2 ? pair.seq1() : pair.seq2();
    }
}
