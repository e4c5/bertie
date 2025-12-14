package com.raditha.dedup.refactoring;

import com.raditha.dedup.model.*;
import com.raditha.dedup.analyzer.DuplicationReport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for automated refactoring.
 * Coordinates validation, refactoring application, and verification.
 */
public class RefactoringEngine {

    private final SafetyValidator validator;
    private final RefactoringVerifier verifier;
    private final DiffGenerator diffGenerator;
    private final RefactoringMode mode;
    private final Path projectRoot;
    private final List<String> dryRunDiffs = new ArrayList<>();

    public RefactoringEngine(Path projectRoot, RefactoringMode mode) {
        this(projectRoot, mode, RefactoringVerifier.VerificationLevel.COMPILE);
    }

    public RefactoringEngine(Path projectRoot, RefactoringMode mode,
            RefactoringVerifier.VerificationLevel verificationLevel) {
        this.projectRoot = projectRoot;
        this.mode = mode;
        this.validator = new SafetyValidator();
        this.verifier = new RefactoringVerifier(projectRoot, verificationLevel);
        this.diffGenerator = new DiffGenerator();
    }

    /**
     * Refactor all duplicates in a report.
     */
    public RefactoringSession refactorAll(DuplicationReport report) {
        RefactoringSession session = new RefactoringSession();

        System.out.println("\n=== Refactoring Session Started ===");
        System.out.println("Mode: " + mode);
        System.out.println("Clusters to process: " + report.clusters().size());
        System.out.println();

        // Sort clusters by importance: larger LOC reduction first, then by similarity
        List<DuplicateCluster> sortedClusters = report.clusters().stream()
                .sorted((c1, c2) -> {
                    // First by LOC reduction (descending)
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

            System.out.printf("Processing cluster #%d (Strategy: %s, Confidence: %.0f%%)%n",
                    i + 1, recommendation.strategy(), recommendation.confidenceScore() * 100);

            // Safety validation
            SafetyValidator.ValidationResult validation = validator.validate(cluster, recommendation);
            if (!validation.isValid()) {
                // For dry-run mode, show warnings but don't skip
                if (mode == RefactoringMode.DRY_RUN) {
                    System.out.println("  ⚠️  Validation warnings (proceeding anyway in dry-run):");
                    validation.getErrors().forEach(e -> System.out.println("     - " + e));
                } else {
                    System.out.println("  ❌ Validation failed:");
                    validation.getErrors().forEach(e -> System.out.println("     - " + e));
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

            // Apply refactoring based on strategy
            try {
                ExtractMethodRefactorer.RefactoringResult result = applyRefactoring(cluster, recommendation);

                if (mode == RefactoringMode.DRY_RUN) {
                    // Collect diff for summary report
                    collectDryRunDiff(cluster, recommendation, result, i + 1);
                    System.out.println("  ✓ Dry-run: Changes not applied");
                    session.addSkipped(cluster, "Dry-run mode");
                    continue;
                }

                // Create backup before writing
                verifier.createBackup(result.sourceFile());

                // Write refactored code
                result.apply();
                System.out.println("  ✓ Refactoring applied");

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
                session.addFailed(cluster, e.getMessage());
                try {
                    verifier.rollback();
                } catch (IOException rollbackEx) {
                    System.err.println("  ⚠️  Rollback failed: " + rollbackEx.getMessage());
                }
            }
        }

        // Show dry-run diff report if in dry-run mode
        if (mode == RefactoringMode.DRY_RUN && !dryRunDiffs.isEmpty()) {
            printDryRunReport();
        }

        System.out.println("\n=== Session Summary ===");
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

        return switch (recommendation.strategy()) {
            case EXTRACT_HELPER_METHOD -> {
                ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
                yield refactorer.refactor(cluster, recommendation);
            }
            case EXTRACT_TO_BEFORE_EACH -> {
                ExtractBeforeEachRefactorer refactorer = new ExtractBeforeEachRefactorer();
                ExtractBeforeEachRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
                // Convert to ExtractMethodRefactorer.RefactoringResult for compatibility
                yield new ExtractMethodRefactorer.RefactoringResult(
                        result.sourceFile(),
                        result.refactoredCode(),
                        recommendation.strategy(),
                        "Extracted to @BeforeEach: " + recommendation.suggestedMethodName());
            }
            case EXTRACT_TO_PARAMETERIZED_TEST -> {
                ExtractParameterizedTestRefactorer refactorer = new ExtractParameterizedTestRefactorer();
                ExtractParameterizedTestRefactorer.RefactoringResult result = refactorer.refactor(cluster,
                        recommendation);
                yield new ExtractMethodRefactorer.RefactoringResult(
                        result.sourceFile(),
                        result.refactoredCode(),
                        recommendation.strategy(),
                        "Extracted to @ParameterizedTest: " + recommendation.suggestedMethodName());
            }
            case EXTRACT_TO_UTILITY_CLASS -> {
                ExtractUtilityClassRefactorer refactorer = new ExtractUtilityClassRefactorer();
                ExtractUtilityClassRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);
                yield new ExtractMethodRefactorer.RefactoringResult(
                        result.sourceFile(),
                        result.refactoredCode(),
                        recommendation.strategy(),
                        "Extracted to utility class: " + result.utilityClassName());
            }
            default -> throw new UnsupportedOperationException(
                    "Refactoring strategy not yet implemented: " + recommendation.strategy());
        };
    }

    /**
     * Show diff and ask user for confirmation (interactive mode).
     */
    private boolean showDiffAndConfirm(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        System.out.println("\n  === PROPOSED REFACTORING ===");
        System.out.println("  Strategy: " + recommendation.strategy());
        System.out.println("  Method: " + recommendation.generateMethodSignature());
        System.out.println("  Confidence: " + recommendation.formatConfidence());
        System.out.println("  LOC Reduction: " + cluster.estimatedLOCReduction());
        System.out.println();

        // Generate and show actual diff
        try {
            ExtractMethodRefactorer.RefactoringResult result = applyRefactoring(cluster, recommendation);
            String diff = diffGenerator.generateUnifiedDiff(result.sourceFile(), result.refactoredCode());

            System.out.println("  === DIFF PREVIEW ===");
            System.out.println(diff);
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

    /**
     * Collect diff for dry-run summary report.
     */
    private void collectDryRunDiff(DuplicateCluster cluster, RefactoringRecommendation recommendation,
            ExtractMethodRefactorer.RefactoringResult result, int clusterNum) {
        try {
            String diff = diffGenerator.generateUnifiedDiff(result.sourceFile(), result.refactoredCode());

            StringBuilder entry = new StringBuilder();
            entry.append(String.format("\n### Cluster #%d: %s ###\n", clusterNum, recommendation.strategy()));
            entry.append(String.format("Confidence: %.0f%%\n", recommendation.confidenceScore() * 100));
            entry.append(String.format("File: %s\n", result.sourceFile().getFileName()));
            entry.append(String.format("Method: %s\n", recommendation.generateMethodSignature()));
            entry.append(diff);
            entry.append("\n");

            dryRunDiffs.add(entry.toString());
        } catch (Exception e) {
            dryRunDiffs.add(String.format("\n### Cluster #%d: ERROR ###\n%s\n", clusterNum, e.getMessage()));
        }
    }

    /**
     * Print dry-run summary report with all diffs.
     */
    private void printDryRunReport() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DRY-RUN SUMMARY REPORT");
        System.out.println("=".repeat(80));
        System.out.println("The following changes would be applied:");

        dryRunDiffs.forEach(System.out::println);

        System.out.println("=".repeat(80));
        System.out.println("Total refactorings previewed: " + dryRunDiffs.size());
        System.out.println("=".repeat(80));
    }
}
