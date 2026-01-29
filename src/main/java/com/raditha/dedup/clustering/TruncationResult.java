package com.raditha.dedup.clustering;

/**
 * Result of sequence truncation analysis.
 * 
 * @param validCount The number of statements that are safe to extract
 * @param returnVariable The return variable identified during truncation (if any)
 */
public record TruncationResult(int validCount, String returnVariable) {
}
