package com.raditha.dedup.model;

import com.raditha.dedup.analysis.EscapeAnalyzer;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Comparator for {@link SimilarityPair} that prioritizes <b>Refactoring Viability</b> 
 * over raw duplicate size.
 * 
 * <p>While the goal of deduplication is to reduce LOC, a slightly smaller 
 * refactoring that is "cleaner" is significantly more valuable than a large 
 * but complex one.</p>
 * 
 * <p><b>Priority Order:</b></p>
 * <ol>
 *   <li><b>Fewest Escapes (Safety):</b> A block that doesn't modify local variables 
 *       needed later is much easier to extract. Complex escapes require DTOs/parameters 
 *       which clutter the code.</li>
 *   <li><b>Full Body Match (Cleanliness):</b> Extracting a whole method body 
 *       is cleaner than extracting a partial block from the middle of a method.</li>
 *   <li><b>Broadest Scope (Coverage):</b> Prefers blocks that cover a larger 
 *       physical range of lines.</li>
 *   <li><b>Largest Size (LOC):</b> Tie-breaker using the count of AST statements.</li>
 *   <li><b>Earliest Appearance:</b> Final tie-breaker for deterministic output.</li>
 * </ol>
 */
public class RefactoringPriorityComparator implements Comparator<SimilarityPair> {

    private final EscapeAnalyzer escapeAnalyzer;
    private final Map<StatementSequence, Integer> escapeCache;

    public RefactoringPriorityComparator() {
        this.escapeAnalyzer = new EscapeAnalyzer();
        this.escapeCache = new IdentityHashMap<>();
    }

    @Override
    public int compare(SimilarityPair a, SimilarityPair b) {
        // Priority 0: Fewest Escapes
        int escapesA = getEscapeCount(a.seq1()) + getEscapeCount(a.seq2());
        int escapesB = getEscapeCount(b.seq1()) + getEscapeCount(b.seq2());
        int escapeCompare = Integer.compare(escapesA, escapesB);
        if (escapeCompare != 0) return escapeCompare;

        // Priority 1: Full Body Match
        boolean bodyA = isFullBody(a.seq1());
        boolean bodyB = isFullBody(b.seq1());
        if (bodyA != bodyB) return bodyA ? -1 : 1;

        // Priority 2: Broadest Scope
        int scopeA = a.seq1().range().endLine() - a.seq1().range().startLine();
        int scopeB = b.seq1().range().endLine() - b.seq1().range().startLine();
        int scopeCompare = Integer.compare(scopeB, scopeA);
        if (scopeCompare != 0) return scopeCompare;

        // Priority 3: Largest Size
        int sizeCompare = Integer.compare(b.seq1().statements().size(), a.seq1().statements().size());
        if (sizeCompare != 0) return sizeCompare;

        // Priority 4: Earliest Appearance
        return Integer.compare(a.seq1().range().startLine(), b.seq1().range().startLine());
    }

    private int getEscapeCount(StatementSequence seq) {
        return escapeCache.computeIfAbsent(seq, s -> escapeAnalyzer.analyze(s).size());
    }

    private boolean isFullBody(StatementSequence seq) {
        return seq.getCallableBody()
                .map(body -> body.getStatements().size() == seq.statements().size())
                .orElse(false);
    }
}
