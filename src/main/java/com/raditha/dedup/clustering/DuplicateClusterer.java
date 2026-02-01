package com.raditha.dedup.clustering;

import com.raditha.dedup.model.*;

import java.util.*;

/**
 * Clusters duplicate detection results by grouping related duplicates together.
 * Groups similarity pairs by their primary sequence (the earliest occurrence) and
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
        // Filter by similarity threshold
        List<SimilarityPair> filtered = pairs.stream()
                .filter(p -> p.similarity().overallScore() >= similarityThreshold)
                .toList();

        // Build adjacency graph for connected components
        Map<StatementSequence, Set<StatementSequence>> adj = new HashMap<>();
        for (SimilarityPair p : filtered) {
             adj.computeIfAbsent(p.seq1(), k -> new HashSet<>()).add(p.seq2());
             adj.computeIfAbsent(p.seq2(), k -> new HashSet<>()).add(p.seq1());
        }

        Set<StatementSequence> visited = new HashSet<>();
        List<DuplicateCluster> clusters = new ArrayList<>();

        List<StatementSequence> orderedNodes = new ArrayList<>(adj.keySet());
        orderedNodes.sort(StatementSequenceComparator.INSTANCE);

        for (StatementSequence node : orderedNodes) {
            if (visited.contains(node)) continue;

            // BFS for connected component
            Set<StatementSequence> component = new HashSet<>();
            Queue<StatementSequence> queue = new LinkedList<>();
            queue.add(node);
            visited.add(node);
            component.add(node);

            while (!queue.isEmpty()) {
                StatementSequence current = queue.poll();
                for (StatementSequence neighbor : adj.getOrDefault(current, Collections.emptySet())) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        component.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            if (component.size() < 2) continue;

            // Find global primary for the component (lowest start line)
            StatementSequence primary = component.stream()
                .min(StatementSequenceComparator.INSTANCE)
                .orElseThrow();

            // Group pairs relevant to this cluster
            List<SimilarityPair> componentPairs = new ArrayList<>();
            for (SimilarityPair p : filtered) {
                if (component.contains(p.seq1()) && component.contains(p.seq2())) {
                     componentPairs.add(p);
                }
            }

            // Calculate LOC reduction
            int totalDuplicateLines = component.stream()
                 .filter(s -> !s.equals(primary))
                 .mapToInt(s -> s.statements().size())
                 .sum();

            int callSiteLines = component.size() - 1;
            int methodOverhead = 1;
            int locReduction = Math.max(0, totalDuplicateLines - callSiteLines - methodOverhead);

            clusters.add(new DuplicateCluster(
                    primary,
                    componentPairs,
                    null,
                    locReduction));
        }

        // Sort by LOC reduction potential (highest first)
        return clusters.stream()
                .sorted((a, b) -> Integer.compare(b.estimatedLOCReduction(), a.estimatedLOCReduction()))
                .toList();
    }
}
