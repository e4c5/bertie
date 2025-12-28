package com.raditha.dedup.config;

import java.util.List;

/**
 * Configuration for duplication detection.
 * Defines thresholds, weights, and filtering rules.
 * 
 * @param minLines        Minimum number of lines for a duplicate sequence
 * @param threshold       Minimum similarity score to report (0.0-1.0)
 * @param weights         Weights for combining similarity metrics
 * @param includeTests    Include test classes in analysis
 * @param excludePatterns File patterns to exclude (glob format)
 * @param maxWindowGrowth Maximum window size growth beyond minLines (for performance tuning)
 * @param maximalOnly     If true, extract only maximal (longest) sequences to ignore smaller duplicates
 */
public record DuplicationConfig(
        int minLines,
        double threshold,
        SimilarityWeights weights,
        boolean includeTests,
        List<String> excludePatterns,
        int maxWindowGrowth,
        boolean maximalOnly) {
    /**
     * Validate configuration.
     */
    public DuplicationConfig {
        if (minLines < 1) {
            throw new IllegalArgumentException("minLines must be >= 1");
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0.0 and 1.0");
        }
        if (weights == null) {
            throw new IllegalArgumentException("weights cannot be null");
        }
        if (excludePatterns == null) {
            excludePatterns = List.of();
        }
        if (maxWindowGrowth < 0) {
            throw new IllegalArgumentException("maxWindowGrowth must be >= 0");
        }
    }

    /**
     * Moderate preset: balanced detection (75% threshold, 5 min lines).
     * Good default for most projects.
     */
    public static DuplicationConfig moderate() {
        return new DuplicationConfig(
                5, // minLines
                0.75, // threshold
                SimilarityWeights.balanced(),
                false, // includeTests
                List.of(), // excludePatterns
                5, // maxWindowGrowth - creates windows from 5 to 10 statements
                true); // maximalOnly - only extract largest sequences
    }

    /**
     * Strict preset: high confidence duplicates only (90% threshold, 7 min lines).
     * Reduces false positives, finds only very similar code.
     */
    public static DuplicationConfig strict() {
        return new DuplicationConfig(
                7, // minLines
                0.90, // threshold
                SimilarityWeights.balanced(),
                false, // includeTests
                List.of(), // excludePatterns
                3, // maxWindowGrowth - smaller for strict mode
                true); // maximalOnly
    }

    /**
     * Lenient preset: catch more potential duplicates (60% threshold, 3 min lines).
     * May have more false positives but finds more refactoring opportunities.
     */
    public static DuplicationConfig lenient() {
        return new DuplicationConfig(
                3, // minLines
                0.60, // threshold
                SimilarityWeights.balanced(),
                false, // includeTests
                List.of(), // excludePatterns
                7, // maxWindowGrowth - larger for lenient mode
                false); // maximalOnly - extract more variations in lenient mode
    }

    /**
     * Aggressive preset: find all potential duplicates.
     * - 3+ lines
     * - 60%+ similarity
     * - Structural weights
     */
    public static DuplicationConfig aggressive() {
        return new DuplicationConfig(
                3,
                0.60,
                SimilarityWeights.structural(),
                true,
                defaultExcludePatterns(),
                10, // maxWindowGrowth - largest for aggressive mode
                false); // maximalOnly - extract all variations in aggressive mode
    }

    /**
     * Test setup preset: tolerant to gaps (extra logging, etc.).
     * - 3+ lines
     * - 70%+ similarity
     * - Lenient weights
     */
    public static DuplicationConfig testSetup() {
        return new DuplicationConfig(
                3,
                0.70,
                SimilarityWeights.lenient(),
                true,
                defaultExcludePatterns(),
                7, // maxWindowGrowth
                true); // maximalOnly
    }

    /**
     * Default file exclusion patterns.
     */
    private static List<String> defaultExcludePatterns() {
        return List.of(
                "**/target/**",
                "**/build/**",
                "**/generated/**",
                "**/gen/**",
                "**/.git/**");
    }

    /**
     * Check if a file path matches any exclusion pattern.
     */
    public boolean shouldExclude(String filePath) {
        for (String pattern : excludePatterns) {
            if (matchesGlobPattern(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simple glob pattern matching.
     * Supports ** and * wildcards.
     */
    private boolean matchesGlobPattern(String path, String pattern) {
        // Convert glob pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*");
        return path.matches(regex);
    }
}
