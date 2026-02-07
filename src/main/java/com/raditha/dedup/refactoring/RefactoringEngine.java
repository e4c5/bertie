package com.raditha.dedup.refactoring;

import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequenceComparator;
import com.raditha.dedup.model.StatementSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Main orchestrator for automated refactoring.
 * Coordinates validation, refactoring application, and verification.
 */
@SuppressWarnings("java:S106")
public class RefactoringEngine {
    private static final Logger logger = LoggerFactory.getLogger(RefactoringEngine.class);
    private final SafetyValidator validator;
    private final RefactoringVerifier verifier;
    private final DiffGenerator diffGenerator;
    private final RefactoringMode mode;
    private final List<String> dryRunDiffs = new ArrayList<>();

    /**
     * Creates a new refactoring engine with default verification level (COMPILE).
     *
     * @param projectRoot The root directory of the project
     * @param mode        The refactoring mode
     */
    public RefactoringEngine(Path projectRoot, RefactoringMode mode) {
        this(projectRoot, mode, com.raditha.dedup.cli.VerifyMode.COMPILE);
    }

    /**
     * Creates a new refactoring engine with specified verification level.
     *
     * @param projectRoot       The root directory of the project
     * @param mode              The refactoring mode
     * @param verificationLevel The level of verification to perform
     */
    public RefactoringEngine(Path projectRoot, RefactoringMode mode,
            com.raditha.dedup.cli.VerifyMode verificationLevel) {
        this(mode, new SafetyValidator(), new RefactoringVerifier(projectRoot, verificationLevel), new DiffGenerator());
    }

    /**
     * Package-private constructor for unit testing.
     */
    RefactoringEngine(RefactoringMode mode, SafetyValidator validator, RefactoringVerifier verifier, DiffGenerator diffGenerator) {
        this.mode = mode;
        this.validator = validator;
        this.verifier = verifier;
        this.diffGenerator = diffGenerator;
    }

    /**
     * Refactor all duplicates in a report.
     */
    public RefactoringSession refactorAll(DuplicationReport report) throws IOException, InterruptedException {

        System.out.println("=== Refactoring Session Started ===");
        System.out.println("Mode: " + mode);
        System.out.println("Clusters to process: " + report.clusters().size());
        System.out.println();

        // Sort clusters by importance
        List<DuplicateCluster> sortedClusters = report.clusters().stream()
                .sorted(new ClusterComparator())
                .toList();

        return processClusters(sortedClusters);
    }

    /**
     * Process a list of duplicate clusters.
     * Accessible by Workflows.
     */
    public RefactoringSession processClusters(List<DuplicateCluster> clusters) throws IOException, InterruptedException {
        RefactoringSession session = new RefactoringSession();

        for (int i = 0; i < clusters.size(); i++) {
            processCluster(clusters.get(i), session, i);
        }

        // Show dry-run diff report if in dry-run mode
        if (mode == RefactoringMode.DRY_RUN && !dryRunDiffs.isEmpty()) {
            printDryRunReport();
        }

        // Only print session summary if we processed standard refactoring via CLI
        // Workflows might aggregate sessions differently
        return session;
    }

    /**
     * Process a single cluster.
     */
    private void processCluster(DuplicateCluster cluster, RefactoringSession session, int index) throws IOException, InterruptedException {
        RefactoringRecommendation recommendation = cluster.recommendation();
        if (!canRefactor(session, recommendation, cluster)) {
            return;
        }
        try {
            MethodExtractor.RefactoringResult result = applyRefactoring(cluster, recommendation);

            if (mode == RefactoringMode.DRY_RUN) {
                // Collect diff for summary report
                collectDryRunDiff(recommendation, result, index + 1);
                System.out.println("  ✓ Dry-run: Changes not applied");
                session.addSkipped(cluster, "Dry-run mode");
                return;
            }

            // Create backups before writing (for all modified files)
            for (Path file : result.modifiedFiles().keySet()) {
                verifier.createBackup(file);
            }

            if (result.description() != null && result.description().startsWith("Skipped")) {
                System.out.println("  ⊘ " + result.description());
                session.addSkipped(cluster, result.description());
                verifier.clearBackups();
                return;
            }

            // Calculate diff stats before applying changes (per file)
            Map<Path, DiffGenerator.DiffStats> diffStatsByFile = new LinkedHashMap<>();
            for (Map.Entry<Path, String> fileEntry : result.modifiedFiles().entrySet()) {
                try {
                    String originalContent = Files.exists(fileEntry.getKey())
                            ? Files.readString(fileEntry.getKey())
                            : "";
                    DiffGenerator.DiffStats stats = diffGenerator.calculateDiffStats(originalContent, fileEntry.getValue());
                    diffStatsByFile.put(fileEntry.getKey(), stats);
                } catch (IOException e) {
                    logger.warn("Could not compute diff stats for {}: {}", fileEntry.getKey(), e.getMessage());
                }
            }

            // Write refactored code to all files
            result.apply();
            System.out.printf("  ✓ Refactoring applied to %d file(s)%n", result.modifiedFiles().size());

            // Verify compilation
            RefactoringVerifier.VerificationResult verify = verifier.verify();
            if (verify.isSuccess()) {
                System.out.println("  ✓ Verification passed");
                session.addSuccess(cluster, result.description(), diffStatsByFile);
                verifier.clearBackups();
            } else {
                System.out.println("  ❌ Verification failed:");
                verify.errors().forEach(e -> System.out.println("     - " + e));
                // Rollback
                verifier.rollback();
                session.addFailed(cluster, String.join("; ", verify.errors()));
            }
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception t) {
            logger.error("  ❌ Refactoring failed: {}", t.getMessage());
            // Ensure checking if rollback is needed in case files offered partial writes
            // (unlikely based on implementation but safe)
            verifier.rollback();
            session.addFailed(cluster, "Exception: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static int comparePrimaryLocation(DuplicateCluster c1, DuplicateCluster c2) {
        if (c1 == null && c2 == null) {
            return 0;
        }
        if (c1 == null) {
            return 1;
        }
        if (c2 == null) {
            return -1;
        }
        StatementSequence s1 = c1.primary();
        StatementSequence s2 = c2.primary();
        if (s1 == null && s2 == null) {
            return 0;
        }
        if (s1 == null) {
            return 1;
        }
        if (s2 == null) {
            return -1;
        }
        return StatementSequenceComparator.INSTANCE.compare(s1, s2);
    }

    boolean canRefactor(RefactoringSession session , RefactoringRecommendation recommendation, DuplicateCluster cluster) {

        if (recommendation == null) {
            session.addSkipped(cluster, "No recommendation generated");
            return false;
        }

        if (recommendation.getStrategy() == RefactoringStrategy.MANUAL_REVIEW_REQUIRED) {
            session.addSkipped(cluster, "Manual review required (risky control flow or complex logic)");
            return false;
        }

        // Safety validation
        SafetyValidator.ValidationResult validation = validator.validate(cluster, recommendation);
        if (!validation.isValid() && mode != RefactoringMode.DRY_RUN) {
            System.out.println("  ⊘ Skipped due to safety validation errors:");
            validation.getErrors().forEach(e -> System.out.println("     - " + e));
            session.addSkipped(cluster, String.join("; ", validation.getErrors()));
            return false;
        }

        if (validation.hasWarnings()) {
            System.out.println("  ⚠️  Warnings:");
            validation.getWarnings().forEach(w -> System.out.println("     - " + w));
        }

        // Interactive mode: show diff and ask for confirmation
        if (mode == RefactoringMode.INTERACTIVE  && !showDiffAndConfirm(cluster, recommendation)) {
            session.addSkipped(cluster, "User rejected");
            return false;
        }

        // Batch mode: only process high-confidence refactorings
        if (mode == RefactoringMode.BATCH && !recommendation.isHighConfidence()) {
            session.addSkipped(cluster, "Low confidence for batch mode");
            return false;
        }
        return true;
    }

    /**
     * Apply the refactoring for a given cluster.
     */
    MethodExtractor.RefactoringResult applyRefactoring(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        return switch (recommendation.getStrategy()) {
            case EXTRACT_HELPER_METHOD -> {
                MethodExtractor refactorer = new MethodExtractor();
                yield refactorer.refactor(cluster, recommendation);
            }
            case CONSTRUCTOR_DELEGATION -> {
                ConstructorExtractor refactorer = new ConstructorExtractor();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_TO_PARAMETERIZED_TEST -> {
                ExtractParameterizedTestRefactorer refactorer = new ExtractParameterizedTestRefactorer();
                ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster,
                        recommendation);
                yield new MethodExtractor.RefactoringResult(
                        result.sourceFile(),
                        result.refactoredCode(),
                        recommendation.getStrategy(),
                        "Extracted to @ParameterizedTest: " + recommendation.getSuggestedMethodName());
            }
            case EXTRACT_TO_UTILITY_CLASS -> {
                UtilityClassExtractor refactorer = new UtilityClassExtractor();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_PARENT_CLASS -> {
                ParentClassExtractor refactorer = new ParentClassExtractor();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_ANONYMOUS_TO_PUBLIC_CLASS, EXTRACT_ANONYMOUS_TO_PARENT_INNER_CLASS -> {
                AnonymousClassExtractor refactorer = new AnonymousClassExtractor();
                yield refactorer.refactor(cluster, recommendation);
            }
            default -> throw new UnsupportedOperationException(
                    "Refactoring strategy not yet implemented: " + recommendation.getStrategy());
        };
    }

    /**
     * Show diff and ask user for confirmation (interactive mode).
     */
    boolean showDiffAndConfirm(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        System.out.println("%n  === PROPOSED REFACTORING ===");
        System.out.println("  Strategy: " + recommendation.getStrategy());
        System.out.println("  Method: " + recommendation.generateMethodSignature());
        System.out.println("  Confidence: " + recommendation.formatConfidence());
        System.out.println("  LOC Reduction: " + cluster.estimatedLOCReduction());
        System.out.println();

        // Generate and show actual diff
        try {
            MethodExtractor.RefactoringResult result = applyRefactoring(cluster, recommendation);
            // For diff preview, show the primary file (first in map)
            Map.Entry<Path, String> primaryFile = result.modifiedFiles().entrySet().iterator().next();
            String diff = diffGenerator.generateUnifiedDiff(primaryFile.getKey(), primaryFile.getValue());

            System.out.println("  === DIFF PREVIEW ===");
            System.out.println(diff);
            if (result.modifiedFiles().size() > 1) {
                System.out.println("  (+ " + (result.modifiedFiles().size() - 1) + " more file(s) will be modified)");
            }
            System.out.println("  " + "=".repeat(70));
        } catch (Exception e) {
            System.out.println("  ⚠️  Could not generate diff preview: " + e.getMessage());
        }
        System.out.println();

        System.out.print("  Apply this refactoring? (y/n): ");
        try {
            int response = System.in.read();
            // Clear buffer
            while (System.in.available() > 0) {
                System.in.read();
            }
            return response == 'y' || response == 'Y';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Collect diff for dry-run summary report.
     */
    void collectDryRunDiff(RefactoringRecommendation recommendation,
                                   MethodExtractor.RefactoringResult result, int clusterNum) {
        try {
            StringBuilder entry = new StringBuilder();
            entry.append(String.format("%n### Cluster #%d: %s ###%n", clusterNum, recommendation.getStrategy()));
            entry.append(String.format("Confidence: %.0f%%%n", recommendation.getConfidenceScore() * 100));
            entry.append(String.format("Files modified: %d%n", result.modifiedFiles().size()));
            entry.append(String.format("Method: %s%n", recommendation.generateMethodSignature()));

            // Show diff for each modified file
            for (Map.Entry<Path, String> fileEntry : result.modifiedFiles().entrySet()) {
                entry.append(String.format("%n--- File: %s ---%n", fileEntry.getKey().getFileName()));
                String diff = diffGenerator.generateUnifiedDiff(fileEntry.getKey(), fileEntry.getValue());
                entry.append(diff);
            }
            entry.append("%n");

            dryRunDiffs.add(entry.toString());
        } catch (Exception e) {
            dryRunDiffs.add(String.format("%n### Cluster #%d: ERROR ###%n%s%n", clusterNum, e.getMessage()));
        }
    }

    /**
     * Print dry-run summary report with all diffs.
     */
    void printDryRunReport() {
        System.out.println("%n" + "=".repeat(80));
        System.out.println("DRY-RUN SUMMARY REPORT");
        System.out.println("=".repeat(80));
        System.out.println("The following changes would be applied:");

        dryRunDiffs.forEach(System.out::println);

        System.out.println("=".repeat(80));
        System.out.println("Total refactorings previewed: " + dryRunDiffs.size());
        System.out.println("=".repeat(80));
    }

    /**
     * Refactoring modes.
     */
    public enum RefactoringMode {
        INTERACTIVE, // Show diffs, ask for confirmation
        BATCH, // Auto-apply high-confidence refactorings
        DRY_RUN // Preview only, no changes
    }

    /**
     * Refactoring session tracking.
     */
    public static class RefactoringSession {
        private final List<RefactoringResult> results = new ArrayList<>();
        private int addedLines;
        private int removedLines;

        /**
         * Record a successful refactoring.
         */
        public void addSuccess(DuplicateCluster cluster, String details) {
            addSuccess(cluster, details, (DiffGenerator.DiffStats) null);
        }

        /**
         * Record a successful refactoring with diff stats.
         */
        public void addSuccess(DuplicateCluster cluster, String details, Map<Path, DiffGenerator.DiffStats> diffStats) {
            DiffGenerator.DiffStats aggregate = aggregateStats(diffStats);
            addSuccess(cluster, details, aggregate);
        }

        /**
         * Record a successful refactoring with aggregated diff stats.
         */
        public void addSuccess(DuplicateCluster cluster, String details, DiffGenerator.DiffStats diffStats) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.SUCCESS, details, diffStats));
            addDiffStats(diffStats);
        }

        /**
         * Record a skipped refactoring.
         */
        public void addSkipped(DuplicateCluster cluster, String reason) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.SKIPPED, reason, null));
        }

        /**
         * Record a failed refactoring.
         */
        public void addFailed(DuplicateCluster cluster, String error) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.FAILED, error, null));
        }

        /**
         * Get successful results.
         */
        public List<RefactoringResult> getSuccessful() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.SUCCESS)
                    .toList();
        }

        /**
         * Get skipped results.
         */
        public List<RefactoringResult> getSkipped() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.SKIPPED)
                    .toList();
        }

        /**
         * Get failed results.
         */
        public List<RefactoringResult> getFailed() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.FAILED)
                    .toList();
        }

        /**
         * Check if any failures occurred.
         */
        public boolean hasFailures() {
            return results.stream().anyMatch(r -> r.status() == RefactoringStatus.FAILED);
        }

        /**
         * Get total items processed.
         */
        public int getTotalProcessed() {
            return results.size();
        }

        /**
         * Total lines added across successful refactorings.
         */
        public int getAddedLines() {
            return addedLines;
        }

        /**
         * Total lines removed across successful refactorings.
         */
        public int getRemovedLines() {
            return removedLines;
        }

        /**
         * Net line change across successful refactorings.
         */
        public int getNetLineChange() {
            return addedLines - removedLines;
        }

        private void addDiffStats(DiffGenerator.DiffStats stats) {
            if (stats == null) {
                return;
            }
            addedLines += stats.addedLines();
            removedLines += stats.removedLines();
        }

        private DiffGenerator.DiffStats aggregateStats(Map<Path, DiffGenerator.DiffStats> diffStats) {
            if (diffStats == null || diffStats.isEmpty()) {
                return null;
            }
            int added = 0;
            int removed = 0;
            for (DiffGenerator.DiffStats stats : diffStats.values()) {
                if (stats == null) {
                    continue;
                }
                added += stats.addedLines();
                removed += stats.removedLines();
            }
            return new DiffGenerator.DiffStats(added, removed);
        }
    }

    /**
     * Status of a refactoring operation.
     */
    public enum RefactoringStatus {
        SUCCESS, SKIPPED, FAILED
    }

    /**
     * Result of a single refactoring operation.
     */
    public record RefactoringResult(DuplicateCluster cluster, RefactoringStatus status, String message,
                                    DiffGenerator.DiffStats diffStats) {
        /**
         * Details of the operation (alias for message).
         */
        public String details() { return message; }

        /**
         * Reason for skipping (alias for message).
         */
        public String reason() { return message; }

        /**
         * Error message (alias for message).
         */
        public String error() { return message; }
    }

    static int getStrategyPriority(RefactoringRecommendation recommendation) {
        if (recommendation == null) return 0;
        return switch (recommendation.getStrategy()) {
            case EXTRACT_TO_PARAMETERIZED_TEST -> 100;
            case EXTRACT_PARENT_CLASS -> 90;
            case EXTRACT_TO_UTILITY_CLASS -> 80;
            case EXTRACT_ANONYMOUS_TO_PARENT_INNER_CLASS -> 85;
            case EXTRACT_ANONYMOUS_TO_PUBLIC_CLASS -> 75;
            case EXTRACT_HELPER_METHOD -> 50;
            default -> 0;
        };
    }

    /**
     * Comparator for selecting the most beneficial clusters first.
     */
    private static class ClusterComparator implements java.util.Comparator<DuplicateCluster> {
        @Override
        public int compare(DuplicateCluster c1, DuplicateCluster c2) {
            // First by Strategy Priority (Parameterized Test > Helper Method)
            // This prevents creating unused helper methods when a parameterized test would consume the duplicates
            int p1 = getStrategyPriority(c1.recommendation());
            int p2 = getStrategyPriority(c2.recommendation());
            int priorityCompare = Integer.compare(p2, p1);
            if (priorityCompare != 0)
                return priorityCompare;

            // Then by LOC reduction (descending)
            int locCompare = Integer.compare(c2.estimatedLOCReduction(), c1.estimatedLOCReduction());
            if (locCompare != 0)
                return locCompare;

            // Then by Statement Count (descending) - Secondary to total savings
            // Check primary sequence size
            int size1 = c1.primary() != null ? c1.primary().statements().size() : 0;
            int size2 = c2.primary() != null ? c2.primary().statements().size() : 0;
            int sizeCompare = Integer.compare(size2, size1);
            if (sizeCompare != 0)
                return sizeCompare;

            // Then by similarity score (descending)
            double sim1 = c1.duplicates().isEmpty() ? 0 : c1.duplicates().get(0).similarity().overallScore();
            double sim2 = c2.duplicates().isEmpty() ? 0 : c2.duplicates().get(0).similarity().overallScore();
            int simCompare = Double.compare(sim2, sim1);
            if (simCompare != 0) {
                return simCompare;
            }
            return comparePrimaryLocation(c1, c2);
        }
    }
}
