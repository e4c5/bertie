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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // Step 2-5: Process sequences through the duplicate detection pipeline
        ProcessedDuplicates processed = processDuplicatePipeline(sequences);

        // Step 6: Create report
        return new DuplicationReport(
                sourceFile,
                processed.duplicates,
                processed.clustersWithRecommendations,
                sequences.size(),
                processed.candidatesCount,
                config);
    }

    /**
     * Analyze the entire project for duplicates, enabling cross-file detection.
     * Uses LSH to scale to large codebases.
     *
     * @param allCUs Map of class name to CompilationUnit
     * @return List of reports, one per file involved in duplicates
     */
    public List<DuplicationReport> analyzeProject(Map<String, CompilationUnit> allCUs) {
        List<StatementSequence> allSequences = new ArrayList<>();
        Map<Path, List<StatementSequence>> fileSequences = new java.util.HashMap<>();
        Set<CompilationUnit> processedCUs = Collections.newSetFromMap(new IdentityHashMap<>());

        // 1. Extract from all files (Lazily normalized)
        for (CompilationUnit cu : allCUs.values()) {
            if (processedCUs.add(cu)) {

                Path sourceFile = cu.getStorage().map(com.github.javaparser.ast.CompilationUnit.Storage::getPath)
                        .orElse(null);

                if (sourceFile == null) {
                    // Skip files without storage information (can't track source path)
                    continue;
                }

                List<StatementSequence> sequences = extractor.extractSequences(cu, sourceFile);
                fileSequences.put(sourceFile, sequences);
                allSequences.addAll(sequences);
            }
        }

        // 2-4. Process sequences through the duplicate detection pipeline
        ProcessedDuplicates processed = processDuplicatePipeline(allSequences);

        // 5. Group by File and Generate Reports
        return distributeReports(fileSequences, processed.duplicates, processed.clustersWithRecommendations, processed.candidatesCount);
    }

    /**
     * Helper record to hold the results of the duplicate detection pipeline.
     */
    private record ProcessedDuplicates(
            List<SimilarityPair> duplicates,
            List<DuplicateCluster> clustersWithRecommendations,
            int candidatesCount
    ) {}

    /**
     * Process sequences through the complete duplicate detection pipeline:
     * 1. Find candidates
     * 2. Filter by threshold
     * 3. Refine boundaries (optional)
     * 4. Remove overlapping duplicates
     * 5. Cluster duplicates
     * 6. Add refactoring recommendations
     *
     * @param sequences List of statement sequences to process
     * @return ProcessedDuplicates containing filtered duplicates, clusters with recommendations, and candidate count
     */
    private ProcessedDuplicates processDuplicatePipeline(List<StatementSequence> sequences) {
        // Step 1: Compare all pairs (with pre-filtering)
        List<SimilarityPair> candidates = findCandidates(sequences);

        // Step 2: Filter by similarity threshold
        List<SimilarityPair> duplicates = filterByThreshold(candidates);

        // Step 3: Refine boundaries (trim usage-only statements) - optional
        if (config.enableBoundaryRefinement()) {
            duplicates = boundaryRefiner.refineBoundaries(duplicates);
        }

        // Step 4: Remove overlapping duplicates (keep only largest)
        duplicates = removeOverlappingDuplicates(duplicates);

        // Step 5: Cluster duplicates
        List<DuplicateCluster> clusters = clusterer.cluster(duplicates);

        // Step 6: Add refactoring recommendations to clusters
        List<DuplicateCluster> clustersWithRecommendations = clusters.stream()
                .map(this::addRecommendation)
                .toList();

        return new ProcessedDuplicates(duplicates, clustersWithRecommendations, candidates.size());
    }

    private List<DuplicationReport> distributeReports(
            Map<Path, List<StatementSequence>> fileSequences,
            List<SimilarityPair> duplicates,
            List<DuplicateCluster> clusters,
            int totalCandidates) {

        Map<Path, List<SimilarityPair>> fileToDuplicates = new java.util.HashMap<>();
        Map<Path, List<DuplicateCluster>> fileToClusters = new java.util.HashMap<>();

        // Initialize with empty lists for all files
        for (Path p : fileSequences.keySet()) {
            fileToDuplicates.put(p, new ArrayList<>());
            fileToClusters.put(p, new ArrayList<>());
        }

        // Distribute duplicates
        for (SimilarityPair pair : duplicates) {
            Path p1 = pair.seq1().sourceFilePath();
            Path p2 = pair.seq2().sourceFilePath();

            if (p1 != null)
                fileToDuplicates.computeIfAbsent(p1, k -> new ArrayList<>()).add(pair);
            if (p2 != null && !p2.equals(p1))
                fileToDuplicates.computeIfAbsent(p2, k -> new ArrayList<>()).add(pair);
        }

        // Distribute clusters
        for (DuplicateCluster cluster : clusters) {
            cluster.allSequences().stream()
                    .map(StatementSequence::sourceFilePath)
                    .distinct()
                    .forEach(path -> {
                        if (path != null) {
                            fileToClusters.computeIfAbsent(path, k -> new ArrayList<>()).add(cluster);
                        }
                    });
        }

        List<DuplicationReport> reports = new ArrayList<>();
        for (Map.Entry<Path, List<StatementSequence>> entry : fileSequences.entrySet()) {
            Path path = entry.getKey();
            List<StatementSequence> seqs = entry.getValue();
            List<SimilarityPair> fileDups = fileToDuplicates.getOrDefault(path, Collections.emptyList());
            List<DuplicateCluster> fileClusters = fileToClusters.getOrDefault(path, Collections.emptyList());

            reports.add(new DuplicationReport(
                    path,
                    fileDups,
                    fileClusters,
                    seqs.size(),
                    totalCandidates,
                    config));
        }
        return reports;
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
     * Find candidate duplicate pairs using either LSH or brute force.
     * Delegates to the appropriate method based on configuration.
     * 
     * @param sequences List of statement sequences to analyze
     * @return List of candidate similarity pairs
     */
    private List<SimilarityPair> findCandidates(List<StatementSequence> sequences) {
        if (config.enableLSH()) {
            return findCandidatesLSH(sequences);
        } else {
            // Brute force fallback - requires full normalization
            List<NormalizedSequence> normalizedSequences = sequences.stream()
                .map(seq -> new NormalizedSequence(
                        seq,
                        astNormalizer.normalize(seq.statements())))
                .toList();
            return findCandidatesBruteForce(normalizedSequences);
        }
    }

    /**
     * Find candidate duplicate pairs using LSH and pre-filtering.
     * Uses FuzzyTokenizer for fast indexing and Lazy Normalization for verification.
     */
    private List<SimilarityPair> findCandidatesLSH(List<StatementSequence> sequences) {
        List<SimilarityPair> candidates = new ArrayList<>();
        com.raditha.dedup.normalization.FuzzyTokenizer tokenizer = new com.raditha.dedup.normalization.FuzzyTokenizer();

        // Cache for strict normalization (computed only on demand for candidates)
        Map<StatementSequence, NormalizedSequence> normalizationCache = new java.util.HashMap<>();

        // 1. Initialize LSH Index
        // Configuration: 50 bands × 2 rows = 100 hash functions
        // Lower rows per band (2 vs 5) increases recall by making collision more likely
        // P(collision) = 1 - (1 - J^r)^b, with r=2,b=50 even moderate similarity leads to collision
        com.raditha.dedup.lsh.MinHash minHash = new com.raditha.dedup.lsh.MinHash(100, 3);
        com.raditha.dedup.lsh.LSHIndex lshIndex = new com.raditha.dedup.lsh.LSHIndex(minHash, 50, 2);

        // 2. Fused Loop: Query and Add
        for (StatementSequence currentSeq : sequences) {
            // Fast Tokenization (no cloning)
            List<String> tokens = tokenizer.tokenize(currentSeq.statements());

            // Query and Add
            Set<StatementSequence> potentialMatches = lshIndex.queryAndAdd(tokens, currentSeq);

            for (StatementSequence candidateSeq : potentialMatches) {
                // Combined check to reduce continue statements
                if (currentSeq == candidateSeq ||
                    isPhysicallyOverlapping(currentSeq, candidateSeq) ||
                    !preFilter.shouldCompare(currentSeq, candidateSeq)) {
                    continue;
                }

                // Lazy Normalization: Only normalize if we have a candidate pair
                NormalizedSequence currentNorm = normalizationCache.computeIfAbsent(currentSeq,
                    s -> new NormalizedSequence(s, astNormalizer.normalize(s.statements())));
                NormalizedSequence candidateNorm = normalizationCache.computeIfAbsent(candidateSeq,
                    s -> new NormalizedSequence(s, astNormalizer.normalize(s.statements())));

                // analyzePair expects (Earlier, Later) conceptually, but implementation is symmetric
                // Passing candidate (earlier) first, then current (later) to match typical discovery order
                SimilarityPair pair = analyzePair(candidateNorm, currentNorm);
                candidates.add(pair);
            }
        }

        return candidates;
    }

    /**
     * robustness check: returns true if sequences are in the same method
     * or if method is null (e.g. static block) and they are in same file
     */
    private boolean isPhysicallyOverlapping(StatementSequence s1, StatementSequence s2) {
        // Different files -> cannot overlap
        if (s1.sourceFilePath() != null && s2.sourceFilePath() != null
                && !s1.sourceFilePath().equals(s2.sourceFilePath())) {
            return false;
        }

        var m1 = s1.containingMethod();
        var m2 = s2.containingMethod();

        if (m1 != null && m2 != null) {
            // Same method -> check if line ranges overlap
            return m1.equals(m2) && rangesOverlap(s1.range(), s2.range());
        }

        // If one or both methods are null (e.g. static initializer logic),
        // check if they are in the same file and their ranges overlap
        if (s1.sourceFilePath() != null && s2.sourceFilePath() != null) {
            return s1.sourceFilePath().equals(s2.sourceFilePath()) && rangesOverlap(s1.range(), s2.range());
        }

        return false;
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

                // Skip sequences that physically overlap
                if (isPhysicallyOverlapping(seq1, seq2) || !preFilter.shouldCompare(seq1, seq2)) {
                    continue;
                }
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
        // CRITICAL FIX: To prevent code loss during extraction, we must ensure
        // that duplicates have the EXACT same number of statements.
        // If they differ in length, they are "similar code" but NOT suitable 
        // for being clustered together for common method extraction.
        int size1 = norm1.sequence().statements().size();
        int size2 = norm2.sequence().statements().size();
        if (size1 != size2) {
            return new SimilarityPair(norm1.sequence(), norm2.sequence(),
                    new SimilarityResult(0.0, 0.0, 0.0, 0.0, size1, size2,
                            com.raditha.dedup.model.VariationAnalysis.builder().build(), null, false));
        }

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
     * Remove overlapping duplicates, keeping only the duplicate with broadest scope in each
     * overlap group.
     * Two duplicates overlap if they involve the same methods and their line ranges
     * overlap.
     * When duplicates overlap, we keep the one with the BROADEST SCOPE (largest line range),
     * which ensures we prefer full method bodies over nested block contents.
     * 
     * OPTIMIZATION: Groups pairs by method-pair to reduce O(N²) to O(G×k²).
     * Since pairsOverlap requires sameMethodPair==true, we can partition by method-pair.
     * Typical case: G=hundreds of method-pairs, k=2-5 pairs/group → G×k² << N²
     */
    private List<SimilarityPair> removeOverlappingDuplicates(List<SimilarityPair> pairs) {
        if (pairs.isEmpty()) {
            return pairs;
        }

        // Group pairs by their canonical method-pair
        // Pairs can only overlap if they involve the same method-pair
        Map<MethodPairKey, List<SimilarityPair>> groups = new java.util.HashMap<>();
        for (SimilarityPair pair : pairs) {
            MethodPairKey key = makeMethodPairKey(pair);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(pair);
        }

        // Process each group independently with O(k²) algorithm
        List<SimilarityPair> result = new ArrayList<>();
        for (List<SimilarityPair> group : groups.values()) {
            result.addAll(removeOverlapsInGroup(group));
        }

        return result;
    }

    /**
     * Create canonical method-pair key (order-independent).
     * (methodA, methodB) should equal (methodB, methodA)
     */
    private MethodPairKey makeMethodPairKey(SimilarityPair pair) {
        var m1 = pair.seq1().containingMethod();
        var m2 = pair.seq2().containingMethod();
        
        // Canonical ordering: use identity hash codes for stable comparison
        int h1 = System.identityHashCode(m1);
        int h2 = System.identityHashCode(m2);
        
        // Always put smaller hash first for canonical ordering
        if (h1 <= h2) {
            return new MethodPairKey(m1, m2);
        } else {
            return new MethodPairKey(m2, m1);
        }
    }

    /**
     * Canonical method-pair key for grouping.
     * Ensures (m1, m2) == (m2, m1) via constructor ordering.
     */
    private record MethodPairKey(
        com.github.javaparser.ast.body.MethodDeclaration method1,
        com.github.javaparser.ast.body.MethodDeclaration method2
    ) {}

    /**
     * Remove overlaps within a single method-pair group using O(k²) algorithm.
     * Since all pairs in the group have sameMethodPair==true, we only check physical overlap.
     */
    private List<SimilarityPair> removeOverlapsInGroup(List<SimilarityPair> group) {
        if (group.size() <= 1) {
            return group;
        }

        // Sort by scope (descending), then statement count, then start line
        List<SimilarityPair> sorted = new ArrayList<>(group);
        sorted.sort((a, b) -> {
            int scopeA = a.seq1().range().endLine() - a.seq1().range().startLine();
            int scopeB = b.seq1().range().endLine() - b.seq1().range().startLine();
            int scopeCompare = Integer.compare(scopeB, scopeA);
            if (scopeCompare != 0) return scopeCompare;
            
            int sizeCompare = Integer.compare(
                    b.seq1().statements().size(),
                    a.seq1().statements().size());
            if (sizeCompare != 0) return sizeCompare;
            
            return Integer.compare(
                    a.seq1().range().startLine(),
                    b.seq1().range().startLine());
        });

        // Filter overlaps - same logic as original O(N²) but within group
        List<SimilarityPair> filtered = new ArrayList<>();
        for (SimilarityPair current : sorted) {
            boolean overlaps = false;
            
            for (SimilarityPair kept : filtered) {
                // Within same method-pair group, only check physical overlap
                // (sameMethodPair is guaranteed true by grouping)
                if (isPhysicallyOverlapping(current.seq1(), kept.seq1()) ||
                    isPhysicallyOverlapping(current.seq2(), kept.seq2())) {
                    overlaps = true;
                    break;
                }
            }
            
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
    /**
     * Check if two duplicate pairs overlap.
     * Pairs overlap if ANY sequence in pair1 overlaps ANY sequence in pair2.
     * This prevents scheduling conflicting refactorings (e.g. Cluster A refactoring Method X
     * while Cluster B also tries to refactor Method X at the same location).
     */
    private boolean pairsOverlap(SimilarityPair pair1, SimilarityPair pair2) {
        // Check if they involve the same methods
        boolean sameMethods = sameMethodPair(pair1, pair2);
        if (!sameMethods) {
            return false;
        }

        // Check if line ranges physically overlap
        return isPhysicallyOverlapping(pair1.seq1(), pair2.seq1()) ||
               isPhysicallyOverlapping(pair1.seq2(), pair2.seq2());
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
