package com.raditha.dedup.refactoring;

import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public RefactoringEngine(Path projectRoot, RefactoringMode mode) {
        this(projectRoot, mode, com.raditha.dedup.cli.VerifyMode.COMPILE);
    }

    public RefactoringEngine(Path projectRoot, RefactoringMode mode,
            com.raditha.dedup.cli.VerifyMode verificationLevel) {
        this.mode = mode;
        this.validator = new SafetyValidator();
        this.verifier = new RefactoringVerifier(projectRoot, verificationLevel);
        this.diffGenerator = new DiffGenerator();
    }

    /**
     * Refactor all duplicates in a report.
     */
    public RefactoringSession refactorAll(DuplicationReport report) throws IOException {

        System.out.println("=== Refactoring Session Started ===");
        System.out.println("Mode: " + mode);
        System.out.println("Clusters to process: " + report.clusters().size());
        System.out.println();

        // Sort clusters by importance: larger statement count first (to handle inclusion), then LOC reduction
        List<DuplicateCluster> sortedClusters = report.clusters().stream()
                .sorted((c1, c2) -> {
                    // First by Strategy Priority (Parameterized Test > Helper Method)
                    // This prevents creating unused helper methods when a parameterized test would consume the duplicates
                    int p1 = getStrategyPriority(c1.recommendation());
                    int p2 = getStrategyPriority(c2.recommendation());
                    int priorityCompare = Integer.compare(p2, p1);
                    if (priorityCompare != 0) return priorityCompare;

                    // Then by Statement Count (descending) - CRITICAL for handling overlapping duplicates
                    // Check primary sequence size
                    int size1 = c1.primary() != null ? c1.primary().statements().size() : 0;
                    int size2 = c2.primary() != null ? c2.primary().statements().size() : 0;
                    int sizeCompare = Integer.compare(size2, size1);
                    if (sizeCompare != 0) return sizeCompare;

                    // Then by LOC reduction (descending)
                    int locCompare = Integer.compare(c2.estimatedLOCReduction(), c1.estimatedLOCReduction());
                    if (locCompare != 0)
                        return locCompare;

                    // Then by similarity score (descending)
                    double sim1 = c1.duplicates().isEmpty() ? 0 : c1.duplicates().get(0).similarity().overallScore();
                    double sim2 = c2.duplicates().isEmpty() ? 0 : c2.duplicates().get(0).similarity().overallScore();
                    return Double.compare(sim2, sim1);
                })
                .toList();

        return processClusters(sortedClusters);
    }

    /**
     * Process a list of duplicate clusters.
     * Accessible by Workflows.
     */
    public RefactoringSession processClusters(List<DuplicateCluster> clusters) throws IOException {
        RefactoringSession session = new RefactoringSession();

        for (int i = 0; i < clusters.size(); i++) {
            DuplicateCluster cluster = clusters.get(i);
            RefactoringRecommendation recommendation = cluster.recommendation();

            if (recommendation == null) {
                System.out.printf("  DEBUG: Cluster #%d skipped: No recommendation generated%n", i + 1);
                session.addSkipped(cluster, "No recommendation generated");
                continue;
            }

            if (recommendation.getStrategy() == RefactoringStrategy.MANUAL_REVIEW_REQUIRED) {
                System.out.printf("  DEBUG: Cluster #%d skipped: Manual review required (%s)%n", 
                    i + 1, recommendation.getSuggestedMethodName());
                session.addSkipped(cluster, "Manual review required (risky control flow or complex logic)");
                continue;
            }

            System.out.printf("Processing cluster #%d (Strategy: %s, Confidence: %.0f%%)%n",
                    i + 1, recommendation.getStrategy(), recommendation.getConfidenceScore() * 100);

            // Safety validation
            SafetyValidator.ValidationResult validation = validator.validate(cluster, recommendation);
            if (!validation.isValid() && mode != RefactoringMode.DRY_RUN) {
                System.out.println("  ⊘ Skipped due to safety validation errors:");
                validation.getErrors().forEach(e -> System.out.println("     - " + e));
                session.addSkipped(cluster, String.join("; ", validation.getErrors()));
                continue;
            }

            if (validation.hasWarnings()) {
                System.out.println("  ⚠️  Warnings:");
                validation.getWarnings().forEach(w -> System.out.println("     - " + w));
            }

            // Interactive mode: show diff and ask for confirmation
            if (mode == RefactoringMode.INTERACTIVE  && !showDiffAndConfirm(cluster, recommendation)) {
                session.addSkipped(cluster, "User rejected");
                continue;
            }

            // Batch mode: only process high-confidence refactorings
            if (mode == RefactoringMode.BATCH && !recommendation.isHighConfidence()) {
                session.addSkipped(cluster, "Low confidence for batch mode");
                continue;
            }

            try {
                ExtractMethodRefactorer.RefactoringResult result = applyRefactoring(cluster, recommendation);

                if (mode == RefactoringMode.DRY_RUN) {
                    // Collect diff for summary report
                    collectDryRunDiff(recommendation, result, i + 1);
                    System.out.println("  ✓ Dry-run: Changes not applied");
                    session.addSkipped(cluster, "Dry-run mode");
                    continue;
                }

                // Create backups before writing (for all modified files)
                for (Path file : result.modifiedFiles().keySet()) {
                    verifier.createBackup(file);
                }

                if (result.description() != null && result.description().startsWith("Skipped")) {
                    System.out.println("  ⊘ " + result.description());
                    session.addSkipped(cluster, result.description());
                    verifier.clearBackups();
                    continue;
                }

                // Write refactored code to all files
                result.apply();
                System.out.printf("  ✓ Refactoring applied to %d file(s)%n", result.modifiedFiles().size());


                // Verify compilation
                RefactoringVerifier.VerificationResult verify = verifier.verify();
                if (verify.isSuccess()) {
                    System.out.println("  ✓ Verification passed");
                    session.addSuccess(cluster, result.description());
                    verifier.clearBackups();
                } else {
                    System.out.println("  ❌ Verification failed:");
                    verify.errors().forEach(e -> System.out.println("     - " + e));
                    // Rollback
                    verifier.rollback();
                    session.addFailed(cluster, String.join("; ", verify.errors()));
                }
            } catch (Exception t) {
                logger.error("  ❌ Refactoring failed: {}", t.getMessage());
                // Ensure checking if rollback is needed in case files offered partial writes
                // (unlikely based on implementation but safe)
                verifier.rollback();
                session.addFailed(cluster, "Exception: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

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
     * Apply the refactoring for a given cluster.
     */
    private ExtractMethodRefactorer.RefactoringResult applyRefactoring(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        return switch (recommendation.getStrategy()) {
            case EXTRACT_HELPER_METHOD -> {
                ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_TO_PARAMETERIZED_TEST -> {
                ExtractParameterizedTestRefactorer refactorer = new ExtractParameterizedTestRefactorer();
                ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster,
                        recommendation);
                yield new ExtractMethodRefactorer.RefactoringResult(
                        result.sourceFile(),
                        result.refactoredCode(),
                        recommendation.getStrategy(),
                        "Extracted to @ParameterizedTest: " + recommendation.getSuggestedMethodName());
            }
            case EXTRACT_TO_UTILITY_CLASS -> {
                ExtractUtilityClassRefactorer refactorer = new ExtractUtilityClassRefactorer();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_PARENT_CLASS -> {
                ExtractParentClassRefactorer refactorer = new ExtractParentClassRefactorer();
                yield refactorer.refactor(cluster, recommendation);
            }
            default -> throw new UnsupportedOperationException(
                    "Refactoring strategy not yet implemented: " + recommendation.getStrategy());
        };
    }

    /**
     * Show diff and ask user for confirmation (interactive mode).
     */
    private boolean showDiffAndConfirm(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        System.out.println("%n  === PROPOSED REFACTORING ===");
        System.out.println("  Strategy: " + recommendation.getStrategy());
        System.out.println("  Method: " + recommendation.generateMethodSignature());
        System.out.println("  Confidence: " + recommendation.formatConfidence());
        System.out.println("  LOC Reduction: " + cluster.estimatedLOCReduction());
        System.out.println();

        // Generate and show actual diff
        try {
            ExtractMethodRefactorer.RefactoringResult result = applyRefactoring(cluster, recommendation);
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
    private void collectDryRunDiff(RefactoringRecommendation recommendation,
            ExtractMethodRefactorer.RefactoringResult result, int clusterNum) {
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
    private void printDryRunReport() {
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

        public void addSuccess(DuplicateCluster cluster, String details) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.SUCCESS, details));
        }

        public void addSkipped(DuplicateCluster cluster, String reason) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.SKIPPED, reason));
        }

        public void addFailed(DuplicateCluster cluster, String error) {
            results.add(new RefactoringResult(cluster, RefactoringStatus.FAILED, error));
        }

        public List<RefactoringResult> getSuccessful() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.SUCCESS)
                    .toList();
        }

        public List<RefactoringResult> getSkipped() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.SKIPPED)
                    .toList();
        }

        public List<RefactoringResult> getFailed() {
            return results.stream()
                    .filter(r -> r.status() == RefactoringStatus.FAILED)
                    .toList();
        }

        public boolean hasFailures() {
            return results.stream().anyMatch(r -> r.status() == RefactoringStatus.FAILED);
        }

        public int getTotalProcessed() {
            return results.size();
        }
    }

    public enum RefactoringStatus {
        SUCCESS, SKIPPED, FAILED
    }

    public record RefactoringResult(DuplicateCluster cluster, RefactoringStatus status, String message) {
        public String details() { return message; }
        public String reason() { return message; }
        public String error() { return message; }
    }

    private int getStrategyPriority(RefactoringRecommendation recommendation) {
        if (recommendation == null) return 0;
        return switch (recommendation.getStrategy()) {
            case EXTRACT_TO_PARAMETERIZED_TEST -> 100;
            case EXTRACT_PARENT_CLASS -> 90;
            case EXTRACT_TO_UTILITY_CLASS -> 80;
            case EXTRACT_HELPER_METHOD -> 50;
            default -> 0;
        };
    }
}
