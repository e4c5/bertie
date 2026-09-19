package com.raditha.dedup.similarity;

import com.raditha.dedup.normalization.NormalizedNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aligns two normalized node sequences of (possibly) different length using the
 * longest common subsequence. Positions that only exist on one side are padded with
 * {@code null} gaps so that the two returned lists always have the same length and
 * index {@code i} of one list corresponds to index {@code i} of the other.
 */
public final class SequenceAligner {

    private SequenceAligner() {
    }

    /**
     * Result of an alignment: two equal-length lists where gaps are {@code null}.
     *
     * @param left    aligned first sequence (gaps are null)
     * @param right   aligned second sequence (gaps are null)
     * @param matches number of positions where both sides are present and structurally equal
     */
    public record Alignment(List<NormalizedNode> left, List<NormalizedNode> right, int matches) {

        /**
         * Number of aligned columns (matches + gaps + substitutions).
         */
        public int length() {
            return left.size();
        }

        /**
         * Number of gap columns, i.e. statements present only on one side.
         */
        public int gaps() {
            int gaps = 0;
            for (int i = 0; i < left.size(); i++) {
                if (left.get(i) == null || right.get(i) == null) {
                    gaps++;
                }
            }
            return gaps;
        }

        /**
         * Structural similarity over the alignment: matched columns divided by the total
         * number of columns. Gaps count as mismatches, so a single inserted statement in an
         * otherwise identical sequence of n statements scores n/(n+1) rather than collapsing
         * to the positional score.
         */
        public double structuralScore() {
            return left.isEmpty() ? 1.0 : (double) matches / left.size();
        }
    }

    /**
     * Align two node lists using LCS on {@link NormalizedNode#structurallyEquals}.
     */
    public static Alignment align(List<NormalizedNode> a, List<NormalizedNode> b) {
        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (a.get(i).structurallyEquals(b.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<NormalizedNode> left = new ArrayList<>();
        List<NormalizedNode> right = new ArrayList<>();
        int matches = 0;
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (a.get(i).structurallyEquals(b.get(j))) {
                left.add(a.get(i++));
                right.add(b.get(j++));
                matches++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                left.add(a.get(i++));
                right.add(null);
            } else {
                left.add(null);
                right.add(b.get(j++));
            }
        }
        while (i < m) {
            left.add(a.get(i++));
            right.add(null);
        }
        while (j < n) {
            left.add(null);
            right.add(b.get(j++));
        }
        return new Alignment(Collections.unmodifiableList(left), Collections.unmodifiableList(right), matches);
    }
}
