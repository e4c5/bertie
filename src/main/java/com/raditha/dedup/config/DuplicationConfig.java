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
 */
public record DuplicationConfig(
        int minLines,
        double threshold,
        SimilarityWeights weights,
        boolean includeTests,
        List<String> excludePatterns) {
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
                List.of()); // excludePatterns
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
                List.of()); // excludePatterns
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
                List.of()); // excludePatterns
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
                defaultExcludePatterns());
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
                defaultExcludePatterns());
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
