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
import com.raditha.dedup.cli.VerifyMode;
import com.raditha.dedup.config.DuplicationDetectorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import javax.tools.*;

/**
 * Verifies refactored code compiles and tests pass.
 * Handles rollback on failure.
 */
public class RefactoringVerifier {
    private static final Logger logger = LoggerFactory.getLogger(RefactoringVerifier.class);

    private final Path projectRoot;
    private final Map<Path, String> backups = new HashMap<>();
    private final List<Path> createdFiles = new ArrayList<>();
    private final VerifyMode verificationLevel;
    private String cachedClasspath;
    private String cachedSourcepath;

    public RefactoringVerifier(Path projectRoot) {
        this(projectRoot, VerifyMode.COMPILE);
    }

    public RefactoringVerifier(Path projectRoot, VerifyMode level) {
        this.projectRoot = projectRoot;
        this.verificationLevel = level;
    }

    /**
     * Create a backup of a file before modification.
     */
    public void createBackup(Path file) throws IOException {
        if (!Files.exists(file)) {
            createdFiles.add(file);
            invalidateCache();
            return;
        }
        String originalContent = Files.readString(file);
        backups.put(file, originalContent);
    }

    /**
     * Verify the project after refactoring.
     */
    public VerificationResult verify() throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();

        if (verificationLevel == VerifyMode.NONE) {
            return new VerificationResult(true, List.of(), "Verification skipped");
        }

        CompilationResult compileResult;
        if (verificationLevel == VerifyMode.FAST_COMPILE) {
             compileResult = runFastCompile();
        } else {
             compileResult = runMavenCompile();
        }

        if (!compileResult.success()) {
            errors.add("Compilation failed:");
            errors.addAll(compileResult.errors());
            return new VerificationResult(false, errors, "Compilation failed");
        }

        // Step 2: Run tests if level is TEST
        if (verificationLevel == VerifyMode.TEST) {
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
     * Run fast in-process compilation using JavaCompiler API.
     */
    CompilationResult runFastCompile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
             return new CompilationResult(false, List.of("No Java compiler provided. Please ensure you are running with a JDK, not a JRE."), "");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

        // Files to compile: Only modified files + newly created files
        List<Path> filesToCompile = new ArrayList<>(backups.keySet());
        filesToCompile.addAll(createdFiles);
        
        if (filesToCompile.isEmpty()) {
            closeFileManager(fileManager);
            return new CompilationResult(true, List.of(), "No files modified");
        }

        // Create temporary directory for compilation output
        Path tempOutput = createTempDirectory(fileManager);
        if (tempOutput == null) {
            return new CompilationResult(false, List.of("Failed to create temp directory"), "");
        }

        try {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(filesToCompile);
            List<String> options = buildCompilerOptions(tempOutput);

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, // default writer (system.err) if not null, but we capture diagnostics
                fileManager,
                diagnostics,
                options,
                null, // no classes to process annotation
                compilationUnits
            );

            boolean success = task.call();
            List<String> errors = extractCompilationErrors(diagnostics);
            
            return new CompilationResult(success, errors, success ? "Fast compilation succeeded" : "Fast compilation failed");
            
        } finally {
            closeFileManager(fileManager);
            deleteDirectoryRecursively(tempOutput);
        }
    }

    /**
     * Close the file manager, ignoring any exceptions.
     */
    private void closeFileManager(StandardJavaFileManager fileManager) {
        try {
            fileManager.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Create a temporary directory for compilation output.
     * Returns null if creation fails (fileManager will be closed).
     */
    @SuppressWarnings("java:S5443") // Files.createTempDirectory() is secure: creates with unique names and restrictive permissions
    private Path createTempDirectory(StandardJavaFileManager fileManager) {
        try {
            return Files.createTempDirectory("bertie-compile-" + System.nanoTime());
        } catch (IOException e) {
            closeFileManager(fileManager);
            return null;
        }
    }

    /**
     * Build compiler options including classpath and Java version.
     */
    private List<String> buildCompilerOptions(Path tempOutput) {
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(getClasspath());
        
        // Add source path so compiler can find other classes in the project
        options.add("-sourcepath");
        options.add(getSourcepath());

        options.add("-d");
        options.add(tempOutput.toString());
        
        // Java version
        String javaVersion = DuplicationDetectorSettings.getJavaVersion();
        if (javaVersion != null) {
             options.add("-source");
             options.add(javaVersion);
             options.add("-target");
             options.add(javaVersion);
        }
        
        return options;
    }

    /**
     * Build the classpath string for compilation.
     */
    String getClasspath() {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }
        StringBuilder cp = new StringBuilder();
        // 1. External dependencies
        for (String jar : MavenHelper.getJarPaths()) {
            cp.append(jar).append(java.io.File.pathSeparator);
        }
        // 2. Project classes (so we can resolve other classes in the project)
        cp.append(projectRoot.resolve("target/classes")).append(java.io.File.pathSeparator);
        cp.append(projectRoot.resolve("target/test-classes"));
        cachedClasspath = cp.toString();
        return cachedClasspath;
    }

    String getSourcepath() {
        if (cachedSourcepath != null) {
            return cachedSourcepath;
        }
        StringBuilder sp = new StringBuilder();
        sp.append(projectRoot.resolve("src/main/java")).append(java.io.File.pathSeparator);
        sp.append(projectRoot.resolve("src/test/java"));
        cachedSourcepath = sp.toString();
        return cachedSourcepath;
    }

    /**
     * Invalidate the cached classpath and sourcepath.
     * Should be called when the project structure changes (e.g., new file created).
     */
    public void invalidateCache() {
        cachedClasspath = null;
        cachedSourcepath = null;
    }

    /**
     * Extract compilation errors from diagnostics.
     */
    private List<String> extractCompilationErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                JavaFileObject source = diagnostic.getSource();
                String sourcePath = (source != null) ? source.toUri().getPath() : "<unknown>";
                errors.add(String.format("%s:%d: %s",
                    sourcePath,
                    diagnostic.getLineNumber(),
                    diagnostic.getMessage(null)));
            }
        }
        return errors;
    }

    void deleteDirectoryRecursively(Path path) {
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp directory: {} {}" , path, e.getMessage());
        }
    }

    /**
     * Run maven compile.
     */
     CompilationResult runMavenCompile() throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("test-compile");
        command.add("-q");
        
        String javaVersion = DuplicationDetectorSettings.getJavaVersion();
        if (javaVersion != null) {
            command.add("-Dmaven.compiler.release=" + javaVersion);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = readOutput(process);

        boolean success = process.waitFor(60, TimeUnit.SECONDS) &&
                process.exitValue() == 0;

        List<String> errors = success ? List.of() : extractErrors(output);
        return new CompilationResult(success, errors, output);

    }

    /**
     * Run maven test.
     */
    TestResult runMavenTest() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("mvn", "test", "-q");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = readOutput(process);

        boolean success = process.waitFor(120, TimeUnit.SECONDS) &&
                process.exitValue() == 0;

        List<String> errors = success ? List.of() : extractErrors(output);
        return new TestResult(success, errors, output);
    }

    /**
     * Read process output.
     */
    String readOutput(Process process) throws IOException {
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
    List<String> extractErrors(String output) {
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
        // Restore modified files
        for (Map.Entry<Path, String> entry : backups.entrySet()) {
            Path file = entry.getKey();
            String originalContent = entry.getValue();

            Files.writeString(file, originalContent);
        }

        // Delete newly created files
        for (Path file : createdFiles) {
            Files.deleteIfExists(file);
        }

        backups.clear();
        createdFiles.clear();
    }

    /**
     * Clear backups (call after successful verification).
     */
    public void clearBackups() {
        backups.clear();
        createdFiles.clear();
    }

    /**
     * Result of verification.
     */
    public record VerificationResult(boolean success, List<String> errors, String message) {
        public boolean isSuccess() {
            return success;
        }
    }

    record CompilationResult(boolean success, List<String> errors, String output) {
    }

    record TestResult(boolean success, List<String> errors, String output) {
    }
}
