package com.raditha.dedup.similarity;

import com.raditha.dedup.model.Token;

import java.util.List;

/**
 * Calculates similarity using Longest Common Subsequence (LCS).
 * Space-optimized dynamic programming implementation.
 */
public class LCSSimilarity {

    /**
     * Calculate LCS-based similarity between two token sequences.
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

        int lcsLength = computeLCSLength(tokens1, tokens2);
        int maxLength = Math.max(tokens1.size(), tokens2.size());

        return (double) lcsLength / maxLength;
    }

    /**
     * Compute LCS length using space-optimized DP.
     * Uses only O(min(m,n)) space instead of O(m*n).
     */
    private int computeLCSLength(List<Token> tokens1, List<Token> tokens2) {
        // Ensure tokens1 is the shorter sequence for space optimization
        TokenPair pair = ensureShorterFirst(tokens1, tokens2);

        int m = pair.shorter.size();
        int n = pair.longer.size();

        // Use rolling array - only need current and previous row
        RollingArrays arrays = new RollingArrays(m);

        for (int j = 1; j <= n; j++) {
            for (int i = 1; i <= m; i++) {
                if (pair.shorter.get(i - 1).semanticallyMatches(pair.longer.get(j - 1))) {
                    arrays.curr[i] = arrays.prev[i - 1] + 1;
                } else {
                    arrays.curr[i] = Math.max(arrays.curr[i - 1], arrays.prev[i]);
                }
            }
            arrays.swap();
        }

        return arrays.prev[m];
    }

    /**
     * Helper to ensure shorter sequence comes first (for space optimization).
     */
    static TokenPair ensureShorterFirst(List<Token> tokens1, List<Token> tokens2) {
        if (tokens1.size() > tokens2.size()) {
            return new TokenPair(tokens2, tokens1);
        }
        return new TokenPair(tokens1, tokens2);
    }

    /**
     * Helper class for rolling arrays to avoid duplicate swap logic.
     */
    static class RollingArrays {
        int[] prev;
        int[] curr;

        RollingArrays(int size) {
            this.prev = new int[size + 1];
            this.curr = new int[size + 1];
        }

        void swap() {
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
    }

    /**
     * Helper record to hold shorter/longer token sequences.
     */
    static record TokenPair(List<Token> shorter, List<Token> longer) {
    }
}
