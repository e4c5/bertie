package com.raditha.dedup.refactoring;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Verifies refactored code compiles and tests pass.
 * Handles rollback on failure.
 */
public class RefactoringVerifier {

    private final Path projectRoot;
    private final Map<Path, String> backups = new HashMap<>();
    private final VerificationLevel verificationLevel;

    public RefactoringVerifier(Path projectRoot) {
        this(projectRoot, VerificationLevel.COMPILE);
    }

    public RefactoringVerifier(Path projectRoot, VerificationLevel level) {
        this.projectRoot = projectRoot;
        this.verificationLevel = level;
    }

    /**
     * Create a backup of a file before modification.
     */
    public void createBackup(Path file) throws IOException {
        String originalContent = Files.readString(file);
        backups.put(file, originalContent);
    }

    /**
     * Verify the project after refactoring.
     */
    public VerificationResult verify() {
        List<String> errors = new ArrayList<>();

        if (verificationLevel == VerificationLevel.NONE) {
            return new VerificationResult(true, List.of(), "Verification skipped");
        }

        // Step 1: Try to compile
        CompilationResult compileResult = runMavenCompile();
        if (!compileResult.success()) {
            errors.add("Compilation failed:");
            errors.addAll(compileResult.errors());
            return new VerificationResult(false, errors, "Compilation failed");
        }

        // Step 2: Run tests if level is TEST
        if (verificationLevel == VerificationLevel.TEST) {
            TestResult testResult = runMavenTest();
            if (!testResult.success()) {
                errors.add("Tests failed:");
                errors.addAll(testResult.errors());
                return new VerificationResult(false, errors, "Tests failed");
            }
        }

        return new VerificationResult(true, List.of(), "Verification successful");
    }

    /**
     * Run maven compile.
     */
    private CompilationResult runMavenCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = readOutput(process);

            boolean success = process.waitFor(60, TimeUnit.SECONDS) &&
                    process.exitValue() == 0;

            List<String> errors = success ? List.of() : extractErrors(output);
            return new CompilationResult(success, errors, output);

        } catch (IOException | InterruptedException e) {
            return new CompilationResult(false,
                    List.of("Failed to run maven: " + e.getMessage()), "");
        }
    }

    /**
     * Run maven test.
     */
    private TestResult runMavenTest() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "test", "-q");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = readOutput(process);

            boolean success = process.waitFor(120, TimeUnit.SECONDS) &&
                    process.exitValue() == 0;

            List<String> errors = success ? List.of() : extractErrors(output);
            return new TestResult(success, errors, output);

        } catch (IOException | InterruptedException e) {
            return new TestResult(false,
                    List.of("Failed to run tests: " + e.getMessage()), "");
        }
    }

    /**
     * Read process output.
     */
    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    /**
     * Extract error messages from maven output.
     */
    private List<String> extractErrors(String output) {
        List<String> errors = new ArrayList<>();
        String[] lines = output.split("\n");

        for (String line : lines) {
            if (line.contains("[ERROR]") || line.contains("FAILED")) {
                errors.add(line.trim());
            }
        }

        return errors.isEmpty() ? List.of("Unknown error occurred") : errors;
    }

    /**
     * Rollback all changes.
     */
    public void rollback() throws IOException {
        System.out.println("Rolling back changes...");

        for (Map.Entry<Path, String> entry : backups.entrySet()) {
            Path file = entry.getKey();
            String originalContent = entry.getValue();

            Files.writeString(file, originalContent);
            System.out.println("  Restored: " + file.getFileName());
        }

        backups.clear();
        System.out.println("Rollback complete");
    }

    /**
     * Clear backups (call after successful verification).
     */
    public void clearBackups() {
        backups.clear();
    }

    /**
     * Verification levels.
     */
    public enum VerificationLevel {
        NONE, // No verification
        COMPILE, // Compile only
        TEST // Compile + run tests
    }

    /**
     * Result of verification.
     */
    public record VerificationResult(boolean success, List<String> errors, String message) {
        public boolean isSuccess() {
            return success;
        }
    }

    /**
     * Result of compilation.
     */
    private record CompilationResult(boolean success, List<String> errors, String output) {
    }

    /**
     * Result of test execution.
     */
    private record TestResult(boolean success, List<String> errors, String output) {
    }
}
