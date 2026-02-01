package com.raditha.dedup.cli;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.model.SimilarityPair;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationDetectorSettings;
import com.raditha.dedup.metrics.MetricsExporter;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.refactoring.RefactoringEngine;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command-line interface for the Duplication Detector.
 * <p>
 * Usage:
 * java -jar duplication-detector.jar [options] <file-or-directory>
 * <p>
 * Configuration priority: CLI arguments > generator.yml > defaults
 */
@Command(name = "bertie", mixinStandardHelpOptions = true, version = "Bertie v1.0.0", description = "Duplicate Code Detector and Refactoring Tool")
@SuppressWarnings("java:S106")
public class BertieCLI implements Callable<Integer> {

    private static final String VERSION = "1.0.0";

    // Global Options
    @Option(names = "--config-file", description = "Use custom configuration file", paramLabel = "<path>")
    private String configFile;

    @Option(names = "--base-path", description = "Override project base path", paramLabel = "<path>")
    private String basePath;

    @Option(names = "--output", description = "Custom output directory", paramLabel = "<path>")
    private String outputPath;

    @Option(names = "--min-lines", description = "Minimum lines to consider (default: 5)", paramLabel = "<n>")
    private int minLines = 0; // 0 = use YAML/default

    @Option(names = "--threshold", description = "Similarity threshold 0-100 (default: 75)", paramLabel = "<n>")
    private int threshold = 0; // 0 = use YAML/default

    @Option(names = "--strict", description = "Strict preset (90%% threshold, 5 lines)")
    private boolean strict = false;

    @Option(names = "--lenient", description = "Lenient preset (60%% threshold, 3 lines)")
    private boolean lenient = false;

    @Option(names = "--json", description = "Output results in JSON format")
    private boolean jsonOutput = false;

    @Option(names = "--export", description = "Export metrics (csv, json, or both)", paramLabel = "<format>")
    private String exportFormat;

    // Command selection
    @Option(names = "refactor", description = "Apply refactorings to eliminate duplicates")
    private boolean refactorCommand = false;

    @Option(names = "--resume", description = "Resume a previously interrupted refactoring session")
    private boolean resumeSession = false;

    // Refactor Options
    @Option(names = "--mode", description = "Refactoring mode: ${COMPLETION-CANDIDATES}", paramLabel = "<mode>", converter = RefactorModeConverter.class)
    private RefactorMode refactorMode = RefactorMode.INTERACTIVE;

    @Option(names = "--verify", description = "Verification level: ${COMPLETION-CANDIDATES}", paramLabel = "<level>", converter = VerifyModeConverter.class)
    private VerifyMode verifyMode = VerifyMode.COMPILE;

    @Option(names = "--java-version", description = "Target Java version (e.g., 17, 21)")
    private String javaVersion;

    @Option(names = "--java-home", description = "Path to specific JDK home")
    private String javaHome;

    /**
     * Picocli call method - executes the main logic.
     * 
     * @return exit code (0 for success, non-zero for errors)
     */
    @Override
    public Integer call() throws Exception {
        // Validate configuration before proceeding
        validateConfiguration();

        // Initialize Settings once, optionally from custom config file
        if (configFile != null) {
            Settings.loadConfigMap(new java.io.File(configFile));
        } else {
            Settings.loadConfigMap();
        }
        if (basePath != null) {
            Settings.setProperty(Settings.BASE_PATH, basePath);
        }
        if (outputPath != null) {
            Settings.setProperty(Settings.OUTPUT_PATH, outputPath);
        }

        // Load configuration once at startup
        String preset = null;
        if (strict) {
            preset = "strict";
        } else if (lenient) {
            preset = "lenient";
        }
        if (preset != null) {
            System.out.println("Using preset: " + preset);
        }
        
        // Pass java settings to DuplicationDetectorSettings (they are handled as regular properties)
        if (javaVersion != null) {
            Settings.setProperty("java_version", javaVersion);
        }
        if (javaHome != null) {
            Settings.setProperty("java_home", javaHome);
        }

        DuplicationDetectorSettings.loadConfig(minLines, threshold, preset);

        // Update verifyMode from Settings if present (and not overridden by CLI -
        // simplified check)
        // We verify if --verify was passed using spec (if available) or just prefer
        // config if set
        Object verifyProp = Settings.getProperty("verify");
        if (verifyProp != null) {
            try {
                this.verifyMode = VerifyMode.fromString(verifyProp.toString());
            } catch (Exception e) {
                System.err.println("Warning: Invalid verify mode in config: " + verifyProp);
            }
        }

        // Parse all source files once
        AbstractCompiler.preProcess();

        // Run detection or refactoring based on command
        if (refactorCommand) {
            runRefactoring();
        } else {
            runAnalysis();
        }

        return 0; // Success

    }

    /**
     * Entry point for the application.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new BertieCLI());

        // Configure error handling
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            // Handle execution exceptions with appropriate exit codes
            if (ex instanceof IllegalArgumentException) {
                commandLine.getErr().println("Configuration error: " + ex.getMessage());
                return 2;
            } else if (ex instanceof IOException) {
                commandLine.getErr().println("I/O error: " + ex.getMessage());
                return 3;
            } else if (ex instanceof InterruptedException) {
                commandLine.getErr().println("Process interrupted: " + ex.getMessage());
                return 4;
            } else {
                commandLine.getErr().println("Error: " + ex.getMessage());
                ex.printStackTrace(commandLine.getErr());
                return 1;
            }
        });

        // Configure parameter exception handler for better error messages
        cmd.setParameterExceptionHandler((ex, args1) -> {
            CommandLine.Help.ColorScheme colorScheme = CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO);
            cmd.getErr().println(colorScheme.errorText(ex.getMessage()));
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, cmd.getErr());
            cmd.getErr().print(cmd.getUsageMessage(colorScheme));
            return 2; // Invalid command line arguments
        });

        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    /**
     * Validate CLI configuration before execution.
     * 
     * @throws IllegalArgumentException if configuration is invalid
     */
    private void validateConfiguration() {
        // Validate threshold range
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("Threshold must be between 0 and 100, got: " + threshold);
        }

        // Validate min-lines
        if (minLines != 0 && minLines < 1) {
            throw new IllegalArgumentException("Min-lines must be positive, got: " + minLines);
        }

        // Validate export format
        if (exportFormat != null && !exportFormat.isEmpty()) {
            String format = exportFormat.toLowerCase();
            if (!format.equals("csv") && !format.equals("json") && !format.equals("both")) {
                throw new IllegalArgumentException(
                        "Export format must be 'csv', 'json', or 'both', got: " + exportFormat);
            }
        }

        // Validate mutually exclusive presets
        if (strict && lenient) {
            throw new IllegalArgumentException("Cannot use both --strict and --lenient presets simultaneously");
        }

        // Validate config file exists if specified
        if (configFile != null && !new java.io.File(configFile).exists()) {
            throw new IllegalArgumentException("Config file not found: " + configFile);
        }

        // Validate base path exists if specified
        if (basePath != null && !new java.io.File(basePath).exists()) {
            throw new IllegalArgumentException("Base path not found: " + basePath);
        }

        validateOutputPath();
    }

    private void validateOutputPath() {
        // Validate output path is writable if specified
        if (outputPath != null) {
            java.io.File outputDir = new java.io.File(outputPath);
            if (outputDir.exists() && !outputDir.isDirectory()) {
                throw new IllegalArgumentException("Output path exists but is not a directory: " + outputPath);
            }
            if (!outputDir.exists()) {
                try {
                    outputDir.mkdirs();
                } catch (SecurityException e) {
                    throw new IllegalArgumentException("Cannot create output directory: " + outputPath);
                }
            }
        }
    }

    private List<DuplicationReport> performAnalysis() {
        // Get compilation units and filter criteria
        Map<String, CompilationUnit> allCUs = AntikytheraRunTime.getResolvedCompilationUnits();
        System.out.println("DEBUG: allCUs detected " + allCUs.size() + " files.");

        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(allCUs);

        // Filter map based on target class
        String targetClass = DuplicationDetectorSettings.getTargetClass();

        Map<String, CompilationUnit> targetCUs = new java.util.HashMap<>(allCUs);
        if (targetClass != null && !targetClass.isEmpty()) {
            String[] targets = java.util.Arrays.stream(targetClass.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toArray(String[]::new);
            
            if (targets.length > 0) {
                System.out.println("DEBUG: Samples of allCUs keys:");
                allCUs.keySet().stream().limit(5).forEach(k -> System.out.println("  - " + k));
                targetCUs.entrySet().removeIf(entry -> {
                    for (String t : targets) {
                        if (entry.getKey().startsWith(t)) {
                            return false; // Keep it
                        }
                    }
                    return true; // Remove it
                });
            }
            System.out.println("DEBUG: Retained " + targetCUs.size() + " files for analysis.");
        }

        return analyzer.analyzeProject(targetCUs);
    }

    private void runAnalysis() throws IOException {
        List<DuplicationReport> reports = performAnalysis();

        // Print the detailed report
        if (jsonOutput) {
            printJsonReport(reports);
        } else {
            printTextReport(reports);
        }

        // Export metrics if requested
        if (exportFormat != null && !exportFormat.isEmpty()) {
            exportMetrics(reports);
        }
    }

    private void runRefactoring() throws IOException, InterruptedException {
        System.out.println("=== PHASE 1: Duplicate Detection ===");
        System.out.println();

        List<DuplicationReport> reports;
        if (resumeSession) {
            System.out.println("Resuming session from .bertie/last_session.json...");
            reports = SessionManager.loadSession(Paths.get(".bertie/last_session.json"));
            if (reports == null) {
                System.err.println("Error: No session found to resume. Running full analysis instead.");
                reports = performAnalysis();
            }
        } else {
            reports = performAnalysis();
            SessionManager.saveSession(reports, Paths.get(".bertie/last_session.json"));
        }

        if (reports.isEmpty()) {
            System.out.println("No files found matching criteria");
            return;
        }

        // Show detection summary
        int totalDuplicates = reports.stream()
                .mapToInt(r -> r.duplicates().size())
                .sum();
        int totalClusters = reports.stream()
                .mapToInt(r -> r.clusters().size())
                .sum();

        System.out.println();
        System.out.printf("Found %d duplicate pairs in %d clusters%n", totalDuplicates, totalClusters);
        System.out.println();

        if (totalClusters == 0) {
            System.out.println("No duplicates found. Nothing to refactor.");
            return;
        }

        // Phase 2: Refactoring
        System.out.println("=== PHASE 2: Automated Refactoring ===");
        System.out.println();

        // Create refactoring engine
        Path projectRoot = Paths.get(Settings.getBasePath());
        RefactoringEngine.RefactoringMode mode = switch (refactorMode) {
            case BATCH -> RefactoringEngine.RefactoringMode.BATCH;
            case DRY_RUN -> RefactoringEngine.RefactoringMode.DRY_RUN;
            case INTERACTIVE -> RefactoringEngine.RefactoringMode.INTERACTIVE;
        };

        // Initialize engine
        RefactoringEngine engine = new RefactoringEngine(
                projectRoot, // Fixed variable name
                mode,
                verifyMode);

        // Initialize Orchestrator (requires analyzer with full project context)
        // We re-initialize analyzer here to ensure we have access to all CUs for re-analysis
        Map<String, CompilationUnit> allCUs = AntikytheraRunTime.getResolvedCompilationUnits();
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(allCUs);
        com.raditha.dedup.workflow.RefactoringOrchestrator orchestrator = 
                new com.raditha.dedup.workflow.RefactoringOrchestrator(analyzer, engine);

        // Process each report
        int totalSuccess = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        for (DuplicationReport report : reports) {
            if (!report.clusters().isEmpty()) {
                System.out.println("Processing file: " + report.sourceFile().getFileName());
                
                // Find valid CU for this report
                // Keys in allCUs are class names, so we must check the Storage path of the CU itself
                Path reportPath = report.sourceFile().toAbsolutePath().normalize();
                 
                CompilationUnit cu = allCUs.values().stream()
                    .filter(unit -> unit.getStorage().map(s -> s.getPath().toAbsolutePath().normalize())
                            .map(path -> path.equals(reportPath))
                            .orElse(false))
                    .findFirst()
                    .orElse(null);
                    
                if (cu != null) {
                    // Use Orchestrator instead of direct engine call
                    RefactoringEngine.RefactoringSession session = orchestrator.orchestrate(report, cu);
    
                    totalSuccess += session.getSuccessful().size();
                    totalSkipped += session.getSkipped().size();
                    totalFailed += session.getFailed().size();
                } else {
                    System.out.println("Warning: Could not find CompilationUnit for " + report.sourceFile() + ". Skipping.");
                }
            }
        }

        // Final summary
        System.out.println();
        System.out.println("=== FINAL SUMMARY ===");
        System.out.printf("✓ Successful refactorings: %d%n", totalSuccess);
        System.out.printf("⊘ Skipped: %d%n", totalSkipped);
        System.out.printf("✗ Failed: %d%n", totalFailed);
        System.out.println();

        if (totalSuccess > 0) {
            System.out.println("Refactoring complete! Please review the changes and run your tests.");
        }
    }


    private static void printTextReport(List<DuplicationReport> reports) {
        int totalDuplicates = reports.stream()
                .mapToInt(DuplicationReport::getDuplicateCount)
                .sum();

        int totalClusters = reports.stream()
                .mapToInt(r -> r.clusters().size())
                .sum();

        System.out.println("=".repeat(80));
        System.out.println("DUPLICATION DETECTION REPORT");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.printf("Files analyzed: %d%n", reports.size());
        System.out.printf("Total duplicates found: %d%n", totalDuplicates);
        System.out.printf("Duplicate clusters: %d%n", totalClusters);
        System.out.printf("Configuration: min-lines=%d, threshold=%.0f%%%n",
                DuplicationDetectorSettings.getMinLines(), DuplicationDetectorSettings.getThreshold() * 100);
        System.out.println();

        if (totalDuplicates == 0) {
            System.out.println("✓ No significant code duplication found!");
            System.out.println();
            return;
        }

        for (DuplicationReport report : reports) {
            showReport(report);
        }

        // Final summary
        System.out.println("=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        int totalLOCReduction = reports.stream()
                .flatMap(r -> r.clusters().stream())
                .mapToInt(DuplicateCluster::estimatedLOCReduction)
                .sum();
        System.out.printf("Total potential LOC reduction: %d lines%n", totalLOCReduction);
        System.out.printf("Refactorable duplicates: %d%n",
                reports.stream()
                        .flatMap(r -> r.duplicates().stream())
                        .filter(p -> p.similarity().canRefactor())
                        .count());
        System.out.println();
    }

    private static void showReport(DuplicationReport report) {
        if (!report.hasDuplicates()) {
            return;
        }

        System.out.println("-".repeat(80));
        System.out.println("File: " + report.sourceFile().getFileName());
        System.out.println("-".repeat(80));
        System.out.println();

        // Show top duplicates with details
        var duplicates = report.duplicates();
        for (int i = 0; i < Math.min(10, duplicates.size()); i++) {
            showDuplications(report, duplicates, i);
        }

        // Show cluster summary
        if (!report.clusters().isEmpty()) {
            System.out.println("REFACTORING OPPORTUNITIES:");
            for (int i = 0; i < report.clusters().size(); i++) {
                var cluster = report.clusters().get(i);
                System.out.printf("  Cluster #%d: %d duplicates, potential %d LOC reduction%n",
                        i + 1,
                        cluster.duplicates().size(),
                        cluster.estimatedLOCReduction());

                if (cluster.recommendation() != null) {
                    var rec = cluster.recommendation();
                    System.out.printf("    → Strategy: %s%n", rec.getStrategy());
                    System.out.printf("    → Confidence: %s%n", rec.formatConfidence());
                    if (rec.getSuggestedMethodName() != null) {
                        System.out.printf("    → Suggested method: %s%n", rec.getSuggestedMethodName());
                    }
                }
            }
            System.out.println();
        }
    }

    private static void showDuplications(DuplicationReport report, List<SimilarityPair> duplicates, int i) {
        var pair = duplicates.get(i);
        var seq1 = pair.seq1();
        var seq2 = pair.seq2();
        var similarity = pair.similarity();

        System.out.printf("DUPLICATE #%d (Similarity: %.1f%%)%n", i + 1,
                similarity.overallScore() * 100);
        System.out.println();

        // Display the duplicated code segment once
        System.out.println("  Duplicated Code:");
        printFullCodeSnippet(seq1.statements());
        System.out.println();

        // List all locations where this duplication appears
        System.out.println("  Found in:");
        printLocation(report, seq1, 1);
        printLocation(report, seq2, 2);
        System.out.println();

        // Similarity breakdown
        System.out.printf("  Similarity: LCS=%.1f%%, Levenshtein=%.1f%%, Structural=%.1f%%%n",
                similarity.lcsScore() * 100,
                similarity.levenshteinScore() * 100,
                similarity.structuralScore() * 100);

        if (similarity.canRefactor()) {
            System.out.println("  ✓ Can be refactored - extract to helper method");
            if (similarity.variations().hasVariations()) {
                System.out.println("  Parameters needed: " +
                        similarity.variations().getVariationCount());
            }
        } else {
            System.out.println("  ⚠ Manual review needed - variations may be complex");
        }

        System.out.println();
    }

    /**
     * Print location information for a code sequence.
     */
    private static void printLocation(DuplicationReport report,
            StatementSequence seq,
            int locNum) {
        Path sourcePath = seq.sourceFilePath() != null ? seq.sourceFilePath() : report.sourceFile();
        String className = extractClassName(sourcePath.toString());
        String methodName = seq.containingCallable() != null ? seq.containingCallable().getNameAsString() : "top-level";
        int startLine = seq.range().startLine();
        int endLine = seq.range().endLine();

        System.out.printf("    %d. Class: %s%n", locNum, className);
        System.out.printf("       Method: %s%n", methodName);
        System.out.printf("       Lines: %d-%d%n", startLine, endLine);
    }

    /**
     * Extract class name from file path.
     */
    private static String extractClassName(String filePath) {
        // Extract from path like .../com/raditha/.../ClassName.java
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        return fileName.replace(".java", "");
    }

    /**
     * Print the full code snippet without truncation.
     */
    private static void printFullCodeSnippet(List<com.github.javaparser.ast.stmt.Statement> statements) {
        if (statements.isEmpty()) {
            System.out.println("    (empty)");
            return;
        }

        for (var stmt : statements) {
            String code = stmt.toString();
            // Print each line with proper indentation
            String[] lines = code.split("\n");
            for (String line : lines) {
                System.out.println("    " + line);
            }
        }
    }

    private static void printJsonReport(List<DuplicationReport> reports) {
        // Simple JSON output (would use proper JSON library in production)
        System.out.println("{");
        System.out.printf("  \"version\": \"%s\",%n", VERSION);
        System.out.printf("  \"filesAnalyzed\": %d,%n", reports.size());
        System.out.printf("  \"totalDuplicates\": %d,%n",
                reports.stream().mapToInt(DuplicationReport::getDuplicateCount).sum());
        System.out.println("  \"files\": [");

        for (int i = 0; i < reports.size(); i++) {
            DuplicationReport report = reports.get(i);
            if (report.hasDuplicates()) {
                System.out.println("    {");
                System.out.printf("      \"path\": \"%s\",%n", report.sourceFile());
                System.out.printf("      \"duplicates\": %d,%n", report.getDuplicateCount());
                System.out.printf("      \"clusters\": %d%n", report.clusters().size());
                System.out.print("    }");
                if (i < reports.size() - 1)
                    System.out.print(",");
                System.out.println();
            }
        }

        System.out.println("  ]");
        System.out.println("}");
    }

    /**
     * Export metrics to CSV/JSON files.
     */
    private void exportMetrics(List<DuplicationReport> reports) throws IOException {

        MetricsExporter exporter = new MetricsExporter();
        String projectName = basePath != null
                ? Paths.get(basePath).getFileName().toString()
                : "project";

        MetricsExporter.ProjectMetrics metrics = exporter.buildMetrics(reports, projectName);

        Path outputDir = outputPath != null
                ? Paths.get(outputPath)
                : Paths.get(".");

        if ("csv".equals(exportFormat) || "both".equals(exportFormat)) {
            Path csvPath = outputDir.resolve("duplication-metrics.csv");
            exporter.exportToCsv(metrics, csvPath);
            System.out.println("\n✓ Metrics exported to: " + csvPath.toAbsolutePath());
        }

        if ("json".equals(exportFormat) || "both".equals(exportFormat)) {
            Path jsonPath = outputDir.resolve("duplication-metrics.json");
            exporter.exportToJson(metrics, jsonPath);
            System.out.println("✓ Metrics exported to: " + jsonPath.toAbsolutePath());
        }
    }

    /**
     * Custom converter for RefactorMode enum to handle CLI string values.
     */
    public static class RefactorModeConverter implements ITypeConverter<RefactorMode> {

        /**
         * Creates a new RefactorModeConverter.
         */
        public RefactorModeConverter() {
        }

        /**
         * Converts string value to RefactorMode.
         *
         * @param value the string value to convert
         * @return the corresponding RefactorMode
         * @throws Exception if conversion fails
         */
        @Override
        public RefactorMode convert(String value) throws Exception {
            return RefactorMode.fromString(value);
        }
    }

    /**
     * Custom converter for VerifyMode enum to handle CLI string values.
     */
    public static class VerifyModeConverter implements ITypeConverter<VerifyMode> {

        /**
         * Creates a new VerifyModeConverter.
         */
        public VerifyModeConverter() {
        }

        /**
         * Converts string value to VerifyMode.
         *
         * @param value the string value to convert
         * @return the corresponding VerifyMode
         * @throws Exception if conversion fails
         */
        @Override
        public VerifyMode convert(String value) throws Exception {
            return VerifyMode.fromString(value);
        }
    }
}
