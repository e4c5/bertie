package com.raditha.dedup.model;

/**
 * Represents a source code range (line and column positions).
 * Simplified wrapper around JavaParser's Range.
 * 
 * @param startLine   Starting line number (1-indexed)
 * @param endLine     Ending line number (1-indexed, inclusive)
 * @param startColumn Starting column number (1-indexed)
 * @param endColumn   Ending column number (1-indexed, inclusive)
 */
public record Range(
        int startLine,
        int endLine,
        int startColumn,
        int endColumn) {
    /**
     * Create from JavaParser Range.
     */
    public static Range from(com.github.javaparser.Range jpRange) {
        return new Range(
                jpRange.begin.line,
                jpRange.end.line,
                jpRange.begin.column,
                jpRange.end.column);
    }

    /**
     * Create from start and end JavaParser ranges.
     */
    public static Range from(com.github.javaparser.Range start, com.github.javaparser.Range end) {
        return new Range(
                start.begin.line,
                end.end.line,
                start.begin.column,
                end.end.column);
    }

    /**
     * Get total number of lines in this range.
     */
    public int getLineCount() {
        return endLine - startLine + 1;
    }

    /**
     * Format as "L45-52" for display.
     */
    public String toDisplayString() {
        if (startLine == endLine) {
            return "L" + startLine;
        }
        return "L" + startLine + "-" + endLine;
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}
