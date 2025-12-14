package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.detection.TokenNormalizer;
import com.raditha.dedup.analysis.VariationTracker;
import com.raditha.dedup.analysis.TypeAnalyzer;
import com.raditha.dedup.extraction.StatementExtractor;
import com.raditha.dedup.filter.PreFilterChain;
import com.raditha.dedup.clustering.DuplicateClusterer;
import com.raditha.dedup.clustering.RefactoringRecommendationGenerator;
import com.raditha.dedup.model.*;
import com.raditha.dedup.similarity.SimilarityCalculator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for duplicate detection.
 * Coordinates extraction, filtering, similarity calculation, and result
 * aggregation.
 */
public class DuplicationAnalyzer {

    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final PreFilterChain preFilter;
    private final TokenNormalizer normalizer;
    private final VariationTracker variationTracker;
    private final SimilarityCalculator similarityCalculator;
    private final TypeAnalyzer typeAnalyzer;
    private final DuplicateClusterer clusterer;
    private final RefactoringRecommendationGenerator recommendationGenerator;

    /**
     * Create analyzer with default configuration.
     */
    public DuplicationAnalyzer() {
        this(DuplicationConfig.moderate());
    }

    /**
     * Create analyzer with custom configuration.
     */
    public DuplicationAnalyzer(DuplicationConfig config) {
        this.config = config;
        this.extractor = new StatementExtractor(config.minLines());
        this.preFilter = new PreFilterChain();
        this.normalizer = new TokenNormalizer();
        this.variationTracker = new VariationTracker();
        this.similarityCalculator = new SimilarityCalculator();
        this.typeAnalyzer = new TypeAnalyzer();
        this.clusterer = new DuplicateClusterer(config.threshold());
        this.recommendationGenerator = new RefactoringRecommendationGenerator();
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
                        normalizer.normalizeStatements(seq.statements())))
                .toList();

        // Step 2: Compare all pairs (with pre-filtering)
        List<SimilarityPair> candidates = findCandidates(normalizedSequences);

        // Step 3: Filter by similarity threshold
        List<SimilarityPair> duplicates = filterByThreshold(candidates);

        // Step 3.5: Remove overlapping duplicates (keep only largest)
        duplicates = removeOverlappingDuplicates(duplicates);

        // Step 4: Cluster duplicates and generate recommendations
        List<DuplicateCluster> clusters = clusterer.cluster(duplicates);

        // Step 5: Add refactoring recommendations to clusters
        List<DuplicateCluster> clustersWithRecommendations = clusters.stream()
                .map(cluster -> {
                    // Get a representative similarity result from the first pair
                    if (!cluster.duplicates().isEmpty()) {
                        SimilarityResult similarity = cluster.duplicates().get(0).similarity();
                        RefactoringRecommendation recommendation = recommendationGenerator
                                .generateRecommendation(cluster, similarity);

                        // Create new cluster with recommendation
                        return new DuplicateCluster(
                                cluster.primary(),
                                cluster.duplicates(),
                                recommendation,
                                cluster.estimatedLOCReduction());
                    }
                    return cluster;
                })
                .toList();

        // Step 6: Create report
        return new DuplicationReport(
                sourceFile,
                duplicates,
                clustersWithRecommendations,
                sequences.size(),
                candidates.size(),
                config);
    }

    /**
     * Helper record to hold a sequence with its pre-computed token list.
     * Avoids redundant normalization during comparisons.
     */
    private record NormalizedSequence(StatementSequence sequence, List<Token> tokens) {
    }

    /**
     * Find candidate duplicate pairs using pre-filtering.
     */
    private List<SimilarityPair> findCandidates(List<NormalizedSequence> normalizedSequences) {
        List<SimilarityPair> candidates = new ArrayList<>();
        int totalComparisons = 0;
        int filteredOut = 0;

        // Compare all pairs
        for (int i = 0; i < normalizedSequences.size(); i++) {
            for (int j = i + 1; j < normalizedSequences.size(); j++) {
                totalComparisons++;

                NormalizedSequence norm1 = normalizedSequences.get(i);
                NormalizedSequence norm2 = normalizedSequences.get(j);

                StatementSequence seq1 = norm1.sequence();
                StatementSequence seq2 = norm2.sequence();

                // Skip sequences from the same method (overlapping windows)
                if (seq1.containingMethod() != null &&
                        seq1.containingMethod().equals(seq2.containingMethod())) {
                    filteredOut++;
                    continue;
                }

                // Pre-filter to skip unlikely matches
                if (!preFilter.shouldCompare(seq1, seq2)) {
                    filteredOut++;
                    continue;
                }

                // Calculate similarity using PRE-COMPUTED tokens
                SimilarityPair pair = analyzePair(norm1, norm2);
                candidates.add(pair);
            }
        }

        System.out.printf("Pre-filtering: %d/%d comparisons filtered (%.1f%%)%n",
                filteredOut, totalComparisons, 100.0 * filteredOut / totalComparisons);

        return candidates;
    }

    /**
     * Analyze a pair of sequences for similarity using pre-computed tokens.
     */
    private SimilarityPair analyzePair(NormalizedSequence norm1, NormalizedSequence norm2) {
        // Use PRE-COMPUTED tokens (no normalization needed!)
        List<Token> tokens1 = norm1.tokens();
        List<Token> tokens2 = norm2.tokens();

        // Track variations
        VariationAnalysis variations = variationTracker.trackVariations(tokens1, tokens2);

        // Analyze type compatibility (Phase 7 implementation)
        TypeCompatibility typeCompat = typeAnalyzer.analyzeTypeCompatibility(variations);

        SimilarityResult similarity = similarityCalculator.calculate(
                tokens1,
                tokens2,
                config.weights(),
                variations,
                typeCompat);

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

        System.out.printf("Overlap removal: %d duplicates removed (kept %d largest)%n",
                pairs.size() - filtered.size(), filtered.size());

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
