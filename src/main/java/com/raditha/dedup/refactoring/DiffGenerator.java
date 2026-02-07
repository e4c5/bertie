package com.raditha.dedup.refactoring;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.AbstractDelta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Generates unified diffs for refactoring previews.
 * Uses java-diff-utils library.
 */
public class DiffGenerator {

    /**
     * Generate a unified diff between original and refactored code.
     *
     * @param originalFile   Path to original file
     * @param refactoredCode Refactored code as string
     * @return Unified diff as string
     */
    public String generateUnifiedDiff(Path originalFile, String refactoredCode) throws IOException {
        List<String> original = Files.readAllLines(originalFile);
        List<String> revised = Arrays.asList(refactoredCode.split("\n"));

        Patch<String> patch = DiffUtils.diff(original, revised);

        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + originalFile.getFileName(),
                "b/" + originalFile.getFileName(),
                original,
                patch,
                3 // context lines
        );

        return String.join("\n", unifiedDiff);
    }

    /**
     * Generate diff with custom context lines.
     */
    public String generateUnifiedDiff(Path originalFile, String refactoredCode, int contextLines)
            throws IOException {
        List<String> original = Files.readAllLines(originalFile);
        List<String> revised = Arrays.asList(refactoredCode.split("\n"));

        Patch<String> patch = DiffUtils.diff(original, revised);

        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + originalFile.getFileName(),
                "b/" + originalFile.getFileName(),
                original,
                patch,
                contextLines);

        return String.join("\n", unifiedDiff);
    }

    /**
     * Generate a side-by-side comparison view (simplified text version).
     */
    public String generateSideBySideDiff(Path originalFile, String refactoredCode) throws IOException {
        List<String> original = Files.readAllLines(originalFile);
        List<String> revised = Arrays.asList(refactoredCode.split("\n"));

        DiffUtils.diff(original, revised);

        StringBuilder result = new StringBuilder();
        result.append(String.format("%-50s | %-50s%n", "ORIGINAL", "REFACTORED"));
        result.append("=".repeat(103)).append("\n");

        // Simplified side-by-side (full implementation would align changes)
        int maxLines = Math.max(original.size(), revised.size());
        for (int i = 0; i < maxLines; i++) {
            String left = i < original.size() ? original.get(i) : "";
            String right = i < revised.size() ? revised.get(i) : "";

            // Truncate long lines
            if (left.length() > 48)
                left = left.substring(0, 45) + "...";
            if (right.length() > 48)
                right = right.substring(0, 45) + "...";

            result.append(String.format("%-50s | %-50s%n", left, right));
        }

        return result.toString();
    }

    /**
     * Calculate added/removed line counts between original and revised content.
     *
     * @param originalContent The current file content
     * @param revisedContent  The refactored content
     * @return DiffStats with added and removed line counts
     */
    public DiffStats calculateDiffStats(String originalContent, String revisedContent) {
        List<String> original = Arrays.asList(originalContent.split("\\R", -1));
        List<String> revised = Arrays.asList(revisedContent.split("\\R", -1));
        Patch<String> patch = DiffUtils.diff(original, revised);
        int added = 0;
        int removed = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            removed += delta.getSource().size();
            added += delta.getTarget().size();
        }
        return new DiffStats(added, removed);
    }

    /**
     * Line-level diff statistics.
     */
    public record DiffStats(int addedLines, int removedLines) {
        public int netLines() {
            return addedLines - removedLines;
        }
    }
}
