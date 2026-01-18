package com.raditha.dedup.refactoring;

import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;

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
    public RefactoringSession refactorAll(DuplicationReport report) throws IOException, InterruptedException {
        RefactoringSession session = new RefactoringSession();

        System.out.println("=== Refactoring Session Started ===");
        System.out.println("Mode: " + mode);
        System.out.println("Clusters to process: " + report.clusters().size());
        System.out.println();

        // Sort clusters by importance: larger statement count first (to handle inclusion), then LOC reduction
        List<DuplicateCluster> sortedClusters = report.clusters().stream()
                .sorted((c1, c2) -> {
                    // First by Statement Count (descending) - CRITICAL for handling overlapping duplicates
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

        for (int i = 0; i < sortedClusters.size(); i++) {
            DuplicateCluster cluster = sortedClusters.get(i);
            RefactoringRecommendation recommendation = cluster.recommendation();

            if (recommendation == null) {
                session.addSkipped(cluster, "No recommendation generated");
                continue;
            }

            if (recommendation.getStrategy() == RefactoringStrategy.MANUAL_REVIEW_REQUIRED) {
                session.addSkipped(cluster, "Manual review required (risky control flow or complex logic)");
                continue;
            }

            System.out.printf("Processing cluster #%d (Strategy: %s, Confidence: %.0f%%)%n",
                    i + 1, recommendation.getStrategy(), recommendation.getConfidenceScore() * 100);

            // Safety validation
            SafetyValidator.ValidationResult validation = validator.validate(cluster, recommendation);
            if (!validation.isValid()) {
                // For dry-run mode, show warnings but don't skip
                if (mode != RefactoringMode.DRY_RUN) {
                    session.addSkipped(cluster, String.join("; ", validation.getErrors()));
                    continue;
                }
            }

            if (validation.hasWarnings()) {
                System.out.println("  ⚠️  Warnings:");
                validation.getWarnings().forEach(w -> System.out.println("     - " + w));
            }

            // Interactive mode: show diff and ask for confirmation
            if (mode == RefactoringMode.INTERACTIVE) {
                if (!showDiffAndConfirm(cluster, recommendation)) {
                    session.addSkipped(cluster, "User rejected");
                    continue;
                }
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
            } catch (Exception e) {
                System.out.println("  ❌ Refactoring failed: " + e.getMessage());
                // Ensure checking if rollback is needed in case files offered partial writes
                // (unlikely based on implementation but safe)
                verifier.rollback();
                session.addFailed(cluster, "Exception: " + e.getMessage());
            }

        }

        // Show dry-run diff report if in dry-run mode
        if (mode == RefactoringMode.DRY_RUN && !dryRunDiffs.isEmpty()) {
            printDryRunReport();
        }

        System.out.println("%n=== Session Summary ===");
        System.out.println("Successful: " + session.successful.size());
        System.out.println("Skipped: " + session.skipped.size());
        System.out.println("Failed: " + session.failed.size());

        return session;
    }

    /**
     * Apply the refactoring for a given cluster.
     */
    private ExtractMethodRefactorer.RefactoringResult applyRefactoring(
            DuplicateCluster cluster, RefactoringRecommendation recommendation) throws IOException {

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
                ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
                yield result;
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
        private final List<RefactoringSuccess> successful = new ArrayList<>();
        private final List<RefactoringSkip> skipped = new ArrayList<>();
        private final List<RefactoringFailure> failed = new ArrayList<>();

        public void addSuccess(DuplicateCluster cluster, String details) {
            successful.add(new RefactoringSuccess(cluster, details));
        }

        public void addSkipped(DuplicateCluster cluster, String reason) {
            skipped.add(new RefactoringSkip(cluster, reason));
        }

        public void addFailed(DuplicateCluster cluster, String error) {
            failed.add(new RefactoringFailure(cluster, error));
        }

        public List<RefactoringSuccess> getSuccessful() {
            return successful;
        }

        public List<RefactoringSkip> getSkipped() {
            return skipped;
        }

        public List<RefactoringFailure> getFailed() {
            return failed;
        }

        public boolean hasFailures() {
            return !failed.isEmpty();
        }

        public int getTotalProcessed() {
            return successful.size() + skipped.size() + failed.size();
        }
    }

    public record RefactoringSuccess(DuplicateCluster cluster, String details) {
    }

    public record RefactoringSkip(DuplicateCluster cluster, String reason) {
    }

    public record RefactoringFailure(DuplicateCluster cluster, String error) {
    }
}
