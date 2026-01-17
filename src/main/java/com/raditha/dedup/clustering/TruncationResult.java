package com.raditha.dedup.clustering;

/**
 * Result of sequence truncation analysis.
 * 
 * @param validCount The number of statements that are safe to extract
 * @param returnVariable The return variable identified during truncation (if any)
 */
public record TruncationResult(int validCount, String returnVariable) {
    
    /**
     * Check if truncation was applied.
     * @return true if the sequence was truncated
     */
    public boolean isTruncated() {
        return validCount != -1;
    }
    
    /**
     * Create a result indicating no truncation needed.
     */
    public static TruncationResult noTruncation() {
        return new TruncationResult(-1, null);
    }
}
