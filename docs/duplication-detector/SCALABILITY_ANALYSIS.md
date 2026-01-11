# Analysis of Duplication Detection Approach (Updated)

## Executive Summary

This analysis re-evaluates the scalability of the Bertie Duplication Detector following recent improvements to the codebase, specifically the **Maximal Sequence Extraction** strategy and **Boundary Refinement**.

**Conclusion**: While recent optimizations have significantly reduced the number of sequences ($N$) extracted from methods, the core detection algorithm remains **$O(N^2)$**. For large enterprise projects (50k+ LOC), this quadratic complexity remains a critical bottleneck. We strongly reaffirm the recommendation to implement **Locality Sensitive Hashing (LSH)**.

## 1. Recent Improvements & Their Impact

### 1.1 Maximal Sequence Extraction (`maximalOnly=true`)
The `StatementExtractor` now defaults to extracting only "maximal" sequences at each position, rather than all possible subsequences.
*   **Old Behavior**: Extracted windows of size 5, 6, 7, ... up to limit.
    *   Sequences per method: $\approx O(L^2)$ where $L$ is method length.
*   **New Behavior**: Extracts only the longest valid sequence starting at each position.
    *   Sequences per method: $\approx O(L)$.
*   **Impact**: drastically reduces $N$ (the total number of sequences to compare). A 50-line method might now yield 45 sequences instead of ~500.
*   **Limitation**: While $N$ is reduced by a constant factor (roughly 10-20x), the comparison logic is still $O(N^2)$. Reducing $N$ by 10x reduces runtime by 100x, which is excellent, but $N$ still scales linearly with project size.

### 1.2 Boundary Refinement
The `BoundaryRefiner` trims usage-only statements from the ends of sequences.
*   **Impact**: improves precision and refactoring safety.
*   **Performance**: Adds a small $O(1)$ overhead per candidate pair but does not affect global complexity.

## 2. The Persistence of the $O(N^2)$ Bottleneck

Despite the reduction in $N$, the `DuplicationAnalyzer` still employs a nested loop structure:

```java
// DuplicationAnalyzer.java
for (int i = 0; i < sequences.size(); i++) {
    for (int j = i + 1; j < sequences.size(); j++) {
        // Compare pair(i, j)
    }
}
```

### Scalability Projection
Assuming `maximalOnly` reduces $N$ significantly:
*   **50,000 LOC Project**:
    *   Estimated $N \approx 40,000$ sequences (assuming ~1 sequence per line of code in methods).
    *   Comparisons: $\approx 800,000,000$ (800 million).
    *   Even if each comparison takes only 1 microsecond (optimistic), the total time is **~13 minutes**.
    *   In reality, comparisons involve object overhead, normalization, and pre-filtering, likely taking 10-50µs.
    *   **Real-world Estimate**: **2 - 10 hours**.

This confirms that **for large projects, the current approach is still not scalable.**

## 3. Recommendation: Implement LSH (MinHash)

To achieve the goal of analyzing large projects in minutes (not hours), we must break the $O(N^2)$ dependency.

**Locality Sensitive Hashing (LSH)** with MinHash remains the correct solution.

### Updated Architecture Recommendation
1.  **Extraction**: Keep `maximalOnly` (it's a good optimization).
2.  **Indexing (New Step)**:
    *   Pass all $N$ sequences through LSH Indexing ($O(N)$).
    *   Generate Candidate Pairs ($C$ pairs, where $C \ll N^2$).
3.  **Verification**:
    *   Compare only Candidate Pairs ($O(C)$).
    *   Apply existing `PreFilterChain` and `SimilarityCalculator`.

### Expected Performance with LSH
*   **50,000 LOC Project**:
    *   Indexing: < 10 seconds.
    *   Candidate Pairs: ~100,000 (typical for sparse duplication).
    *   Verification: ~10 seconds.
    *   **Total Time**: **< 1 minute**.

## 4. Implementation Next Steps
The LSH implementation plan outlined in `IMPLEMENTATION_PLAN.md` is correct and should be executed immediately. No changes to the plan are needed, but the priority is validated.
