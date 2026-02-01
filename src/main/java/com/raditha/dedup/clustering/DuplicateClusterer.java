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
     * 
     * This method identifies "connected components" of duplicate pairs. Two pairs are 
     * in the same cluster if they share a common sequence or are connected through 
     * a chain of similar sequences.
     * 
     * Implementation Note: We use an Adjacency Graph + BFS approach rather than 
     * Disjoint Set Union (DSU) for better debuggability and easier tracking of 
     * connectivity relations, as the number of duplicates typically remains 
     * manageable for BFS.
     * 
     * @param pairs List of duplicate pairs to cluster
     * @return List of clusters sorted by LOC reduction potential (highest first)
     */
    public List<DuplicateCluster> cluster(List<SimilarityPair> pairs) {
        // Step 1: Filter pairs and build adjacency graph
        GraphData graphData = buildAdjacencyGraph(pairs);
        if (graphData.adj().isEmpty()) {
            return Collections.emptyList();
        }

        // Step 2: Find connected components (clusters of sequences)
        List<Set<StatementSequence>> components = findConnectedComponents(graphData.adj());

        // Step 3: Efficiently group original pairs by their component
        Map<Set<StatementSequence>, List<SimilarityPair>> componentToPairs = groupPairsByComponent(graphData.filteredPairs(), components);

        // Step 4: Create cluster objects with calculated metrics
        List<DuplicateCluster> clusters = buildClusters(components, componentToPairs);

        // Step 5: Sort by LOC reduction potential (highest first)
        return sortClustersByReduction(clusters);
    }

    /**
     * Filters pairs by similarity threshold and builds an undirected adjacency graph.
     */
    private GraphData buildAdjacencyGraph(List<SimilarityPair> pairs) {
        List<SimilarityPair> filtered = new ArrayList<>();
        Map<StatementSequence, Set<StatementSequence>> adj = new HashMap<>();

        for (SimilarityPair p : pairs) {
            if (p.similarity().overallScore() >= similarityThreshold) {
                filtered.add(p);
                adj.computeIfAbsent(p.seq1(), k -> new HashSet<>()).add(p.seq2());
                adj.computeIfAbsent(p.seq2(), k -> new HashSet<>()).add(p.seq1());
            }
        }
        return new GraphData(filtered, adj);
    }

    /**
     * Identifies sets of related sequences using Breadth-First Search.
     */
    private List<Set<StatementSequence>> findConnectedComponents(Map<StatementSequence, Set<StatementSequence>> adj) {
        Set<StatementSequence> visited = new HashSet<>();
        List<Set<StatementSequence>> components = new ArrayList<>();

        // Sort nodes to ensure deterministic cluster primary selection
        List<StatementSequence> orderedNodes = new ArrayList<>(adj.keySet());
        orderedNodes.sort(StatementSequenceComparator.INSTANCE);

        for (StatementSequence node : orderedNodes) {
            if (!visited.contains(node)) {
                components.add(bfs(node, adj, visited));
            }
        }
        return components;
    }

    private Set<StatementSequence> bfs(StatementSequence startNode, 
                                      Map<StatementSequence, Set<StatementSequence>> adj, 
                                      Set<StatementSequence> visited) {
        Set<StatementSequence> component = new HashSet<>();
        Queue<StatementSequence> queue = new LinkedList<>();
        
        queue.add(startNode);
        visited.add(startNode);
        component.add(startNode);

        while (!queue.isEmpty()) {
            StatementSequence current = queue.poll();
            for (StatementSequence neighbor : adj.getOrDefault(current, Collections.emptySet())) {
                if (visited.add(neighbor)) {
                    component.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return component;
    }

    /**
     * Groups similarity pairs into their respective components.
     * Uses a mapping of sequence -> component for O(N) performance.
     */
    private Map<Set<StatementSequence>, List<SimilarityPair>> groupPairsByComponent(
            List<SimilarityPair> pairs, 
            List<Set<StatementSequence>> components) {
        
        // Build a lookup map to find the component for any given sequence
        Map<StatementSequence, Set<StatementSequence>> sequenceToComponent = new HashMap<>();
        for (Set<StatementSequence> component : components) {
            for (StatementSequence seq : component) {
                sequenceToComponent.put(seq, component);
            }
        }

        Map<Set<StatementSequence>, List<SimilarityPair>> groups = new HashMap<>();
        for (SimilarityPair pair : pairs) {
            Set<StatementSequence> component = sequenceToComponent.get(pair.seq1());
            if (component != null) {
                groups.computeIfAbsent(component, k -> new ArrayList<>()).add(pair);
            }
        }
        return groups;
    }

    /**
     * Transforms component sets into DuplicateCluster objects with metrics.
     */
    private List<DuplicateCluster> buildClusters(
            List<Set<StatementSequence>> components,
            Map<Set<StatementSequence>, List<SimilarityPair>> componentToPairs) {
        
        List<DuplicateCluster> clusters = new ArrayList<>();
        for (Set<StatementSequence> component : components) {
            if (component.size() < 2) continue;

            StatementSequence primary = component.stream()
                    .min(StatementSequenceComparator.INSTANCE)
                    .orElseThrow();

            List<SimilarityPair> pairs = componentToPairs.getOrDefault(component, Collections.emptyList());
            int locReduction = calculateLocReduction(component, primary);

            clusters.add(new DuplicateCluster(primary, pairs, null, locReduction));
        }
        return clusters;
    }

    /**
     * Estimates potential line reduction if this cluster is refactored into a single method.
     */
    private int calculateLocReduction(Set<StatementSequence> component, StatementSequence primary) {
        int totalDuplicateLines = component.stream()
                .filter(s -> !s.equals(primary))
                .mapToInt(s -> s.statements().size())
                .sum();

        int callSiteLines = component.size() - 1;
        int methodOverhead = 1;
        return Math.max(0, totalDuplicateLines - callSiteLines - methodOverhead);
    }

    private List<DuplicateCluster> sortClustersByReduction(List<DuplicateCluster> clusters) {
        return clusters.stream()
                .sorted((a, b) -> Integer.compare(b.estimatedLOCReduction(), a.estimatedLOCReduction()))
                .toList();
    }

    /**
     * Internal DTO to hold graph construction results.
     */
    private record GraphData(
        List<SimilarityPair> filteredPairs, 
        Map<StatementSequence, Set<StatementSequence>> adj) {}
}
