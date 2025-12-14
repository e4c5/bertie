package com.raditha.dedup.analyzer;

import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.SimilarityPair;

import java.nio.file.Path;
import java.util.List;

/**
 * Report containing duplicate detection results for a file.
 * Contains both flat list of duplicate pairs and clustered results with
 * recommendations.
 */
public record DuplicationReport(
        Path sourceFile,
        List<SimilarityPair> duplicates,
        List<DuplicateCluster> clusters,
        int totalSequences,
        int candidatesAnalyzed,
        DuplicationConfig config) {

    /**
     * Get count of duplicate pairs found.
     */
    public int getDuplicateCount() {
        return duplicates.size();
    }

    /**
     * Check if any duplicates were found.
     */
    public boolean hasDuplicates() {
        return !duplicates.isEmpty();
    }

    /**
     * Get duplicates with similarity above a specific threshold.
     */
    public List<SimilarityPair> getDuplicatesAbove(double threshold) {
        return duplicates.stream()
                .filter(pair -> pair.similarity().overallScore() >= threshold)
                .toList();
    }

    /**
     * Get summary statistics.
     */
    public String getSummary() {
        return String.format(
                "Found %d duplicates in %d clusters from %d sequences (%d candidates analyzed, threshold: %.0f%%)",
                duplicates.size(),
                clusters.size(),
                totalSequences,
                candidatesAnalyzed,
                config.threshold() * 100);
    }

    /**
     * Get detailed report string.
     */
    public String getDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(80)).append("\n");
        sb.append("DUPLICATION DETECTION REPORT\n");
        sb.append("=".repeat(80)).append("\n\n");

        sb.append("File: ").append(sourceFile).append("\n");
        sb.append("Min Lines: ").append(config.minLines()).append("\n");
        sb.append("Threshold: ").append(String.format("%.0f%%", config.threshold() * 100)).append("\n");
        sb.append("\n");

        sb.append(getSummary()).append("\n\n");

        if (duplicates.isEmpty()) {
            sb.append("No duplicates found.\n");
        } else {
            sb.append("Duplicates (sorted by similarity):\n");
            sb.append("-".repeat(80)).append("\n\n");

            for (int i = 0; i < duplicates.size(); i++) {
                SimilarityPair pair = duplicates.get(i);
                sb.append(String.format("Pair #%d - %.1f%% similar\n",
                        i + 1,
                        pair.similarity().overallScore() * 100));

                sb.append(String.format("  Sequence 1: Lines %d-%d (%d statements)\n",
                        pair.seq1().range().startLine(),
                        pair.seq1().range().endLine(),
                        pair.seq1().statements().size()));
                sb.append(String.format("  Sequence 2: Lines %d-%d (%d statements)\n",
                        pair.seq2().range().startLine(),
                        pair.seq2().range().endLine(),
                        pair.seq2().statements().size()));

                sb.append(String.format("  Details: LCS=%.1f%%, Levenshtein=%.1f%%, Structural=%.1f%%\n",
                        pair.similarity().lcsScore() * 100,
                        pair.similarity().levenshteinScore() * 100,
                        pair.similarity().structuralScore() * 100));

                sb.append(String.format("  Variations: %d detected\n",
                        pair.similarity().variations().variations().size()));

                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
