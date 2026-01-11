package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analysis.BoundaryRefiner;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.extraction.StatementExtractor;
import com.raditha.dedup.filter.PreFilterChain;
import com.raditha.dedup.clustering.DuplicateClusterer;
import com.raditha.dedup.clustering.RefactoringRecommendationGenerator;
import com.raditha.dedup.model.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Main orchestrator for duplicate detection.
 * Coordinates extraction, filtering, similarity calculation, and result
 * aggregation.
 */
public class DuplicationAnalyzer {
    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final PreFilterChain preFilter;
    private final com.raditha.dedup.normalization.ASTNormalizer astNormalizer; // NEW: AST-based
    private final com.raditha.dedup.similarity.ASTSimilarityCalculator astSimilarityCalculator; // NEW: AST-based
    private final DuplicateClusterer clusterer;
    private final RefactoringRecommendationGenerator recommendationGenerator;
    private final BoundaryRefiner boundaryRefiner;

    /**
     * Create analyzer with default configuration.
     */
    public DuplicationAnalyzer() {
        this(DuplicationConfig.moderate(), Collections.emptyMap());
    }

    /**
     * Create analyzer with custom configuration.
     */
    public DuplicationAnalyzer(DuplicationConfig config) {
        this(config, Collections.emptyMap());
    }

    public DuplicationAnalyzer(DuplicationConfig config, Map<String, CompilationUnit> allCUs) {
        this.config = config;
        this.extractor = new StatementExtractor(config.minLines(), config.maxWindowGrowth(), config.maximalOnly());
        this.preFilter = new PreFilterChain();
        this.astNormalizer = new com.raditha.dedup.normalization.ASTNormalizer(); // NEW
        this.astSimilarityCalculator = new com.raditha.dedup.similarity.ASTSimilarityCalculator(); // NEW
        this.clusterer = new DuplicateClusterer(config.threshold());
        this.recommendationGenerator = new RefactoringRecommendationGenerator(allCUs);
        this.boundaryRefiner = new BoundaryRefiner(
                new DataFlowAnalyzer(),
                config.minLines(),
                config.threshold());
    }

    /**
     * Analyze a single file for duplicates.
     * 
     * @param cu         Compilation unit to analyze
     * @param sourceFile Path to source file
     * @return Analysis report with clustered duplicates and refactoring
     *         recommendations
     */
    public DuplicationReport analyzeFile(CompilationUnit cu, Path sourceFile) {
        // Step 1: Extract all statement sequences
        List<StatementSequence> sequences = extractor.extractSequences(cu, sourceFile);

        // Step 1.5: PRE-NORMALIZE ALL SEQUENCES ONCE (major performance optimization)
        // This avoids normalizing the same sequence multiple times during comparisons
        List<NormalizedSequence> normalizedSequences = sequences.stream()
                .map(seq -> new NormalizedSequence(
                        seq,
                        astNormalizer.normalize(seq.statements()))) // NEW: AST normalization
                .toList();

        // Step 2: Compare all pairs (with pre-filtering)
        List<SimilarityPair> candidates;
        if (config.enableLSH()) {
            candidates = findCandidatesLSH(normalizedSequences);
        } else {
            candidates = findCandidatesBruteForce(normalizedSequences);
        }

        // Step 3: Filter by similarity threshold
        List<SimilarityPair> duplicates = filterByThreshold(candidates);

        // Step 3.5: Refine boundaries (trim usage-only statements)
        if (config.enableBoundaryRefinement()) {
            duplicates = boundaryRefiner.refineBoundaries(duplicates);
        }

        // Step 3.6: Remove overlapping duplicates (keep only largest)
        duplicates = removeOverlappingDuplicates(duplicates);

        // Step 4: Cluster duplicates and generate recommendations
        List<DuplicateCluster> clusters = clusterer.cluster(duplicates);

        // Step 5: Add refactoring recommendations to clusters
        List<DuplicateCluster> clustersWithRecommendations = clusters.stream()
                .map(this::addRecommendation).toList();

        // Step 6: Create report
        return new DuplicationReport(
                sourceFile,
                duplicates,
                clustersWithRecommendations,
                sequences.size(),
                candidates.size(),
                config);
    }

    private DuplicateCluster addRecommendation(DuplicateCluster cluster) {
        // Get a representative similarity result from the first pair
        if (!cluster.duplicates().isEmpty()) {
            RefactoringRecommendation recommendation = recommendationGenerator
                    .generateRecommendation(cluster);

            // Create new cluster with recommendation
            return new DuplicateCluster(
                    cluster.primary(),
                    cluster.duplicates(),
                    recommendation,
                    cluster.estimatedLOCReduction());
        }
        return cluster;
    }

    /**
     * Helper record to hold a sequence with its pre-computed normalized AST.
     * Avoids redundant normalization during comparisons.
     */
    private record NormalizedSequence(
            StatementSequence sequence,
            java.util.List<com.raditha.dedup.normalization.NormalizedNode> normalizedNodes // NEW: AST nodes
    ) {
    }

    /**
     * Find candidate duplicate pairs using LSH and pre-filtering.
     * Optimized with pre-computed maps and stable IDs.
     */
    private List<SimilarityPair> findCandidatesLSH(List<NormalizedSequence> normalizedSequences) {
        List<SimilarityPair> candidates = new ArrayList<>();

        // 1. Pre-compute maps for O(1) lookups and caching
        // Map: StatementSequence -> Integer ID (for stable pair keys)
        java.util.IdentityHashMap<StatementSequence, Integer> sequenceIds = new java.util.IdentityHashMap<>();
        // Map: StatementSequence -> NormalizedSequence (for O(1) retrieval)
        java.util.IdentityHashMap<StatementSequence, NormalizedSequence> seqToNorm = new java.util.IdentityHashMap<>();
        // Map: StatementSequence -> Tokens (avoid repeated fuzzy normalization)
        java.util.IdentityHashMap<StatementSequence, List<String>> seqToTokens = new java.util.IdentityHashMap<>();

        for (int i = 0; i < normalizedSequences.size(); i++) {
            NormalizedSequence norm = normalizedSequences.get(i);
            StatementSequence seq = norm.sequence();

            sequenceIds.put(seq, i); // Stable ID based on list index
            seqToNorm.put(seq, norm);
            seqToTokens.put(seq, extractTokens(norm)); // Computed once per sequence
        }

        // 2. Initialize and Populate LSH Index
        // Using 100 permutations for MinHash
        // Using k-shingles of size 3 (trigrams)
        // Using 20 bands of 5 rows each (requires 100 permutations)
        // This threshold ~ (1/20)^(1/5) = 0.55 similarity
        com.raditha.dedup.lsh.MinHash minHash = new com.raditha.dedup.lsh.MinHash(100, 3);
        com.raditha.dedup.lsh.LSHIndex lshIndex = new com.raditha.dedup.lsh.LSHIndex(minHash, 20, 5);

        for (NormalizedSequence normSeq : normalizedSequences) {
            StatementSequence seq = normSeq.sequence();
            lshIndex.add(seqToTokens.get(seq), seq);
        }

        // 3. Query LSH and Collect Candidates
        java.util.Set<String> processedPairs = new java.util.HashSet<>();

        for (int i = 0; i < normalizedSequences.size(); i++) {
            NormalizedSequence normSeq = normalizedSequences.get(i);
            StatementSequence seq1 = normSeq.sequence();
            int id1 = i;

            List<String> tokens = seqToTokens.get(seq1);

            // Get candidates from LSH bucket
            java.util.Set<StatementSequence> potentialMatches = lshIndex.query(tokens);

            for (StatementSequence seq2 : potentialMatches) {
                // Avoid self-comparison
                if (seq1 == seq2)
                    continue;

                Integer id2 = sequenceIds.get(seq2);
                if (id2 == null)
                    continue; // Should effectively never happen

                // Stable pair key using sorted integer IDs
                String pairKey = getPairKey(id1, id2);
                if (processedPairs.contains(pairKey))
                    continue;
                processedPairs.add(pairKey);

                // Skip sequences from the same method (overlapping windows)
                if (seq1.containingMethod() != null &&
                        seq1.containingMethod().equals(seq2.containingMethod())) {
                    continue;
                }

                // Pre-filter to skip unlikely matches
                if (!preFilter.shouldCompare(seq1, seq2)) {
                    continue;
                }

                // Retrieve NormalizedSequence from map (O(1))
                NormalizedSequence norm2 = seqToNorm.get(seq2);

                if (norm2 != null) {
                    SimilarityPair pair = analyzePair(normSeq, norm2);
                    candidates.add(pair);
                }
            }
        }

        return candidates;
    }

    private String getPairKey(int id1, int id2) {
        return (id1 < id2) ? id1 + "-" + id2 : id2 + "-" + id1;
    }

    private List<String> extractTokens(NormalizedSequence normSeq) {
        // Use FUZZY normalization for LSH to robustly find candidates with variable
        // renames
        List<com.raditha.dedup.normalization.NormalizedNode> fuzzyNodes = astNormalizer
                .normalizeFuzzy(normSeq.sequence().statements());

        List<String> tokens = new ArrayList<>();
        for (com.raditha.dedup.normalization.NormalizedNode node : fuzzyNodes) {
            // NormalizedNode.normalized() returns the Statement with replaced (fuzzy)
            // tokens
            tokens.add(node.normalized().toString());
        }
        return tokens;
    }

    /**
     * Find candidate duplicate pairs using O(N^2) brute force comparison.
     * Fallback when LSH is disabled.
     */
    private List<SimilarityPair> findCandidatesBruteForce(List<NormalizedSequence> normalizedSequences) {
        List<SimilarityPair> candidates = new ArrayList<>();

        // Compare all pairs
        for (int i = 0; i < normalizedSequences.size(); i++) {
            for (int j = i + 1; j < normalizedSequences.size(); j++) {
                NormalizedSequence norm1 = normalizedSequences.get(i);
                NormalizedSequence norm2 = normalizedSequences.get(j);

                StatementSequence seq1 = norm1.sequence();
                StatementSequence seq2 = norm2.sequence();

                // Skip sequences from the same method
                if (seq1.containingMethod() != null &&
                        seq1.containingMethod().equals(seq2.containingMethod())) {
                    continue;
                }

                // Pre-filter
                if (!preFilter.shouldCompare(seq1, seq2)) {
                    continue;
                }

                // Calculate similarity
                SimilarityPair pair = analyzePair(norm1, norm2);
                candidates.add(pair);
            }
        }
        return candidates;
    }

    /**
     * Analyze a pair of sequences for similarity using pre-computed normalized AST.
     */
    private SimilarityPair analyzePair(NormalizedSequence norm1, NormalizedSequence norm2) {
        // Use PRE-COMPUTED normalized nodes (no normalization needed!)
        var nodes1 = norm1.normalizedNodes();
        var nodes2 = norm2.normalizedNodes();

        // Calculate similarity using AST-based calculator
        SimilarityResult similarity = astSimilarityCalculator.calculate(
                nodes1,
                nodes2,
                config.weights());

        return new SimilarityPair(norm1.sequence(), norm2.sequence(), similarity);
    }

    /**
     * Filter pairs by similarity threshold.
     */
    private List<SimilarityPair> filterByThreshold(List<SimilarityPair> candidates) {
        return candidates.stream()
                .filter(pair -> pair.similarity().overallScore() >= config.threshold())
                .sorted((a, b) -> Double.compare(
                        b.similarity().overallScore(),
                        a.similarity().overallScore()))
                .toList();
    }

    /**
     * Remove overlapping duplicates, keeping only the largest duplicate in each
     * overlap group.
     * Two duplicates overlap if they involve the same methods and their line ranges
     * overlap.
     * When duplicates overlap, we keep the one with the most statements.
     */
    private List<SimilarityPair> removeOverlappingDuplicates(List<SimilarityPair> pairs) {
        if (pairs.isEmpty()) {
            return pairs;
        }

        List<SimilarityPair> filtered = new ArrayList<>();
        List<SimilarityPair> sorted = new ArrayList<>(pairs);

        // Sort by size (largest first), then by line number
        sorted.sort((a, b) -> {
            int sizeCompare = Integer.compare(
                    b.seq1().statements().size(),
                    a.seq1().statements().size());
            if (sizeCompare != 0)
                return sizeCompare;
            return Integer.compare(
                    a.seq1().range().startLine(),
                    b.seq1().range().startLine());
        });

        // Keep track of which pairs we've already covered
        for (SimilarityPair current : sorted) {
            boolean overlaps = false;

            // Check if current overlaps with any already-kept pair
            for (SimilarityPair kept : filtered) {
                if (pairsOverlap(current, kept)) {
                    overlaps = true;
                    break;
                }
            }

            // Only keep if it doesn't overlap with a larger duplicate
            if (!overlaps) {
                filtered.add(current);
            }
        }
        return filtered;
    }

    /**
     * Check if two duplicate pairs overlap.
     * Pairs overlap if they involve the same two methods and their line ranges
     * overlap.
     */
    private boolean pairsOverlap(SimilarityPair pair1, SimilarityPair pair2) {
        // Check if they involve the same methods
        boolean sameMethods = sameMethodPair(pair1, pair2);
        if (!sameMethods) {
            return false;
        }

        // Check if line ranges overlap
        return rangesOverlap(pair1.seq1().range(), pair2.seq1().range()) ||
                rangesOverlap(pair1.seq2().range(), pair2.seq2().range());
    }

    /**
     * Check if two pairs involve the same pair of methods.
     */
    private boolean sameMethodPair(SimilarityPair pair1, SimilarityPair pair2) {
        // Get methods for pair1
        var method1a = pair1.seq1().containingMethod();
        var method1b = pair1.seq2().containingMethod();

        // Get methods for pair2
        var method2a = pair2.seq1().containingMethod();
        var method2b = pair2.seq2().containingMethod();

        if (method1a == null || method1b == null || method2a == null || method2b == null) {
            return false;
        }

        // Check if same pair (order doesn't matter)
        return (method1a.equals(method2a) && method1b.equals(method2b)) ||
                (method1a.equals(method2b) && method1b.equals(method2a));
    }

    /**
     * Check if two line ranges overlap.
     */
    private boolean rangesOverlap(Range range1, Range range2) {
        // Ranges overlap if one starts before the other ends
        return range1.startLine() <= range2.endLine() &&
                range2.startLine() <= range1.endLine();
    }

    /**
     * Get the configuration used by this analyzer.
     */
    public DuplicationConfig getConfig() {
        return config;
    }
}
