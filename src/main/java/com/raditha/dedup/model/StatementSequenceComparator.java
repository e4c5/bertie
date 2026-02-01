package com.raditha.dedup.model;

import java.util.Comparator;

/**
 * Standard comparator for {@link StatementSequence} based on physical location.
 * 
 * <p>This provides a <b>Spatial Index</b> for the duplication detection pipeline.
 * It is primarily used to ensure:
 * <ul>
 *   <li><b>Determinism:</b> Results are 100% reproducible regardless of discovery order.</li>
 *   <li><b>Canonical Pairing:</b> Ensures that in any duplicate pair (A, B), 
 *       seq1 is always the one appearing earlier in the source code.</li>
 *   <li><b>LSH Stability:</b> Provides a stable indexing order for Locality Sensitive Hashing.</li>
 * </ul>
 */
public class StatementSequenceComparator implements Comparator<StatementSequence> {

    public static final StatementSequenceComparator INSTANCE = new StatementSequenceComparator();

    private static final Comparator<StatementSequence> INTERNAL_COMP = Comparator
            .comparing((StatementSequence s) -> s.sourceFilePath() == null ? "" : s.sourceFilePath().toString())
            .thenComparingInt(s -> s.range() == null ? 0 : s.range().startLine())
            .thenComparingInt(s -> s.range() == null ? 0 : s.range().startColumn())
            .thenComparingInt(s -> s.range() == null ? 0 : s.range().endLine())
            .thenComparingInt(s -> s.range() == null ? 0 : s.range().endColumn())
            .thenComparingInt(StatementSequence::startOffset)
            .thenComparingInt(s -> s.statements() == null ? 0 : s.statements().size());

    @Override
    public int compare(StatementSequence s1, StatementSequence s2) {
        return INTERNAL_COMP.compare(s1, s2);
    }
}
