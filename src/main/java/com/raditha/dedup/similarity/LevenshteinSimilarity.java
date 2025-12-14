package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;

import java.util.List;

import static com.raditha.dedup.similarity.LCSSimilarity.*;

/**
 * Calculates similarity using Levenshtein edit distance.
 * Space-optimized dynamic programming implementation.
 */
public class LevenshteinSimilarity {

    /**
     * Calculate Levenshtein-based similarity between two token sequences.
     * 
     * @param tokens1 First token sequence
     * @param tokens2 Second token sequence
     * @return Similarity score (0.0 to 1.0)
     */
    public double calculate(List<Token> tokens1, List<Token> tokens2) {
        if (tokens1 == null || tokens2 == null) {
            return 0.0;
        }

        if (tokens1.isEmpty() && tokens2.isEmpty()) {
            return 1.0;
        }

        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }

        int distance = computeEditDistance(tokens1, tokens2);
        int maxLength = Math.max(tokens1.size(), tokens2.size());

        // Convert distance to similarity: similarity = 1 - (distance / maxLength)
        return 1.0 - ((double) distance / maxLength);
    }

    /**
     * Compute Levenshtein edit distance using space-optimized DP.
     * Uses only O(min(m,n)) space instead of O(m*n).
     */
    private int computeEditDistance(List<Token> tokens1, List<Token> tokens2) {
        // Ensure tokens1 is the shorter sequence for space optimization
        TokenPair pair = ensureShorterFirst(tokens1, tokens2);

        int m = pair.shorter().size();
        int n = pair.longer().size();

        // Use rolling array - only need current and previous row
        RollingArrays arrays = new RollingArrays(m);

        // Initialize first row
        for (int i = 0; i <= m; i++) {
            arrays.prev[i] = i;
        }

        for (int j = 1; j <= n; j++) {
            arrays.curr[0] = j; // First column

            for (int i = 1; i <= m; i++) {
                if (pair.shorter().get(i - 1).semanticallyMatches(pair.longer().get(j - 1))) {
                    // No change needed
                    arrays.curr[i] = arrays.prev[i - 1];
                } else {
                    // Min of: delete, insert, replace
                    arrays.curr[i] = 1 + Math.min(
                            Math.min(arrays.prev[i], arrays.curr[i - 1]), // delete or insert
                            arrays.prev[i - 1] // replace
                    );
                }
            }

            arrays.swap();
        }

        return arrays.prev[m];
    }
}
