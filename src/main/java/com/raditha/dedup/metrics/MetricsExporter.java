package com.raditha.dedup.metrics;

import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports duplication metrics to CSV and JSON formats for dashboard integration
 * and historical tracking.
 */
public class MetricsExporter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Project-level metrics aggregated from all analyzed files.
     */
    public record ProjectMetrics(
            String projectName,
            LocalDateTime timestamp,
            int totalFiles,
            int totalDuplicates,
            int totalClusters,
            int totalLOCReduction,
            double averageSimilarity,
            List<FileMetrics> files) {
    }

    /**
     * Per-file duplication metrics.
     */
    public record FileMetrics(
            String fileName,
            int duplicateCount,
            int clusterCount,
            int estimatedLOCReduction,
            double avgSimilarity,
            List<String> refactoringStrategies) {
    }

    /**
     * Build aggregated metrics from analysis reports.
     */
    public ProjectMetrics buildMetrics(List<DuplicationReport> reports, String projectName) {
        List<FileMetrics> fileMetrics = reports.stream()
                .map(this::buildFileMetrics)
                .toList();

        int totalDuplicates = reports.stream()
                .mapToInt(r -> r.duplicates().size())
                .sum();

        int totalClusters = reports.stream()
                .mapToInt(r -> r.clusters().size())
                .sum();

        int totalLOCReduction = reports.stream()
                .flatMap(r -> r.clusters().stream())
                .mapToInt(DuplicateCluster::estimatedLOCReduction)
                .sum();

        double avgSimilarity = reports.stream()
                .flatMap(r -> r.duplicates().stream())
                .mapToDouble(p -> p.similarity().overallScore())
                .average()
                .orElse(0.0);

        return new ProjectMetrics(
                projectName,
                LocalDateTime.now(),
                reports.size(),
                totalDuplicates,
                totalClusters,
                totalLOCReduction,
                avgSimilarity,
                fileMetrics);
    }

    /**
     * Build metrics for a single file.
     */
    private FileMetrics buildFileMetrics(DuplicationReport report) {
        String fileName = report.sourceFile().getFileName().toString();

        double avgSimilarity = report.duplicates().stream()
                .mapToDouble(p -> p.similarity().overallScore())
                .average()
                .orElse(0.0);

        List<String> strategies = report.clusters().stream()
                .map(DuplicateCluster::recommendation)
                .filter(Objects::nonNull)
                .map(rec -> rec.strategy().name())
                .distinct()
                .toList();

        int locReduction = report.clusters().stream()
                .mapToInt(DuplicateCluster::estimatedLOCReduction)
                .sum();

        return new FileMetrics(
                fileName,
                report.duplicates().size(),
                report.clusters().size(),
                locReduction,
                avgSimilarity,
                strategies);
    }

    /**
     * Export metrics to CSV format.
     */
    public void exportToCsv(ProjectMetrics metrics, Path outputPath) throws IOException {
        StringBuilder csv = new StringBuilder();

        // Header - Summary
        csv.append("# Project Summary\n");
        csv.append(
                "timestamp,project,total_files,total_duplicates,total_clusters,total_loc_reduction,avg_similarity\n");
        csv.append(String.format("%s,%s,%d,%d,%d,%d,%.2f\n",
                metrics.timestamp().format(TIMESTAMP_FORMAT),
                metrics.projectName(),
                metrics.totalFiles(),
                metrics.totalDuplicates(),
                metrics.totalClusters(),
                metrics.totalLOCReduction(),
                metrics.averageSimilarity()));

        csv.append("\n");

        // Header - Per-file metrics
        csv.append("# Per-File Metrics\n");
        csv.append("file,duplicates,clusters,loc_reduction,avg_similarity,strategies\n");

        for (FileMetrics file : metrics.files()) {
            String strategies = file.refactoringStrategies().isEmpty()
                    ? "NONE"
                    : String.join(";", file.refactoringStrategies());

            csv.append(String.format("%s,%d,%d,%d,%.2f,%s\n",
                    file.fileName(),
                    file.duplicateCount(),
                    file.clusterCount(),
                    file.estimatedLOCReduction(),
                    file.avgSimilarity(),
                    strategies));
        }

        Files.writeString(outputPath, csv.toString());
    }

    /**
     * Export metrics to JSON format.
     */
    public void exportToJson(ProjectMetrics metrics, Path outputPath) throws IOException {
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append(String.format("  \"timestamp\": \"%s\",\n",
                metrics.timestamp().format(TIMESTAMP_FORMAT)));
        json.append(String.format("  \"project\": \"%s\",\n", metrics.projectName()));

        json.append("  \"summary\": {\n");
        json.append(String.format("    \"totalFiles\": %d,\n", metrics.totalFiles()));
        json.append(String.format("    \"totalDuplicates\": %d,\n", metrics.totalDuplicates()));
        json.append(String.format("    \"totalClusters\": %d,\n", metrics.totalClusters()));
        json.append(String.format("    \"totalLOCReduction\": %d,\n", metrics.totalLOCReduction()));
        json.append(String.format("    \"averageSimilarity\": %.2f\n", metrics.averageSimilarity()));
        json.append("  },\n");

        json.append("  \"files\": [\n");

        for (int i = 0; i < metrics.files().size(); i++) {
            FileMetrics file = metrics.files().get(i);
            json.append("    {\n");
            json.append(String.format("      \"fileName\": \"%s\",\n", file.fileName()));
            json.append(String.format("      \"duplicateCount\": %d,\n", file.duplicateCount()));
            json.append(String.format("      \"clusterCount\": %d,\n", file.clusterCount()));
            json.append(String.format("      \"estimatedLOCReduction\": %d,\n", file.estimatedLOCReduction()));
            json.append(String.format("      \"avgSimilarity\": %.2f,\n", file.avgSimilarity()));

            json.append("      \"strategies\": [");
            if (!file.refactoringStrategies().isEmpty()) {
                json.append(file.refactoringStrategies().stream()
                        .map(s -> "\"" + s + "\"")
                        .collect(Collectors.joining(", ")));
            }
            json.append("]\n");

            json.append(i < metrics.files().size() - 1 ? "    },\n" : "    }\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(outputPath, json.toString());
    }
}
