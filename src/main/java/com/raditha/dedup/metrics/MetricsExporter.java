package com.raditha.dedup.metrics;

import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

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

    /**
     * PrettyPrinter that ensures no space before colon and exactly one space after it,
     * matching tests that look for patterns like "field": "value".
     */
    private static class ColonPrettyPrinter extends com.fasterxml.jackson.core.util.DefaultPrettyPrinter {
        private static final long serialVersionUID = 1L;

        public ColonPrettyPrinter() { super(); }

        protected ColonPrettyPrinter(ColonPrettyPrinter base) {
            super(base);
        }

        @Override
        public com.fasterxml.jackson.core.util.DefaultPrettyPrinter createInstance() {
            return new ColonPrettyPrinter(this);
        }

        @Override
        public void writeObjectFieldValueSeparator(com.fasterxml.jackson.core.JsonGenerator g) throws java.io.IOException {
            g.writeRaw(": ");
        }
    }

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
     * Export metrics to JSON format using Jackson.
     */
    public void exportToJson(ProjectMetrics metrics, Path outputPath) throws IOException {
        // Configure Jackson ObjectMapper with Java Time support and the required timestamp pattern
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.datatype.jsr310.JavaTimeModule timeModule = new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule();
        timeModule.addSerializer(java.time.LocalDateTime.class,
                new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(TIMESTAMP_FORMAT));
        mapper.registerModule(timeModule);
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Build a structure that matches the previous JSON shape
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", metrics.timestamp());
        root.put("project", metrics.projectName());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalFiles", metrics.totalFiles());
        summary.put("totalDuplicates", metrics.totalDuplicates());
        summary.put("totalClusters", metrics.totalClusters());
        summary.put("totalLOCReduction", metrics.totalLOCReduction());
        summary.put("averageSimilarity", metrics.averageSimilarity());
        root.put("summary", summary);

        List<Map<String, Object>> files = new ArrayList<>();
        for (FileMetrics f : metrics.files()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("fileName", f.fileName());
            fm.put("duplicateCount", f.duplicateCount());
            fm.put("clusterCount", f.clusterCount());
            fm.put("estimatedLOCReduction", f.estimatedLOCReduction());
            fm.put("avgSimilarity", f.avgSimilarity());
            fm.put("strategies", f.refactoringStrategies());
            files.add(fm);
        }
        root.put("files", files);

        ColonPrettyPrinter printer = new ColonPrettyPrinter();
        ObjectWriter writer = mapper.writer(printer);
        writer.writeValue(outputPath.toFile(), root);
    }
}
