# Analysis of Duplication Detection Approach (Updated)

## Executive Summary

This analysis re-evaluates the scalability of the Bertie Duplication Detector following recent improvements to the codebase, specifically the **Maximal Sequence Extraction** strategy and **Boundary Refinement**.

**Conclusion**: Recent optimizations significantly reduced the number of sequences ($N$) extracted from methods, and **Locality Sensitive Hashing (LSH) is now implemented and enabled by default** (`MinHash`, `LSHIndex`, `DuplicationAnalyzer.findCandidatesLSH`). The $O(N^2)$ nested-loop comparison (`findCandidatesBruteForce`) is retained only as an opt-in fallback (`enable_lsh: false`) and as the recall baseline for tests. Remaining work is tuning and validation on large real-world projects, not implementation.

## 1. Recent Improvements & Their Impact

### 1.1 Maximal Sequence Extraction (`maximalOnly=true`)
The `StatementExtractor` now defaults to extracting only "maximal" sequences at each position, rather than all possible subsequences.
*   **Old Behavior**: Extracted windows of size 5, 6, 7, ... up to limit.
    *   Sequences per method: $\approx O(L^2)$ where $L$ is method length.
*   **New Behavior**: Extracts only the longest valid sequence starting at each position.
    *   Sequences per method: $\approx O(L)$.
*   **Impact**: drastically reduces $N$ (the total number of sequences to compare). A 50-line method might now yield 45 sequences instead of ~500.
*   **Note**: $N$ is reduced by a constant factor (roughly 10-20x). Combined with LSH candidate generation (Section 2), the verification cost now scales with the number of colliding pairs $C$ rather than $N^2$.

### 1.2 Boundary Refinement
The `BoundaryRefiner` trims usage-only statements from the ends of sequences.
*   **Impact**: improves precision and refactoring safety.
*   **Performance**: Adds a small $O(1)$ overhead per candidate pair but does not affect global complexity.

## 2. The $O(N^2)$ Bottleneck Has Been Removed (LSH Is Implemented)

> **Status update**: LSH candidate generation is **implemented and enabled by default**. The
> earlier version of this document described the nested `for i / for j` loop as the only path;
> that is no longer accurate.

`DuplicationAnalyzer.findCandidates` now dispatches on `DuplicationDetectorSettings.getEnableLSH()`
(`enable_lsh`, default `true`):

```java
// DuplicationAnalyzer.java
private List<SimilarityPair> findCandidates(List<StatementSequence> sequences) {
    if (DuplicationDetectorSettings.getEnableLSH()) {
        return findCandidatesLSH(sequences);      // O(N) indexing + O(C) verification
    }
    return findCandidatesBruteForce(sequences);   // O(N^2) fallback, enable_lsh=false only
}
```

### 2.1 Implemented components
| Component | Location | Role |
|-----------|----------|------|
| `MinHash` | `com.raditha.dedup.lsh.MinHash` | k-shingle (k=3) MinHash signatures over `FuzzyTokenizer` output; `numHashes = num_bands * rows_per_band` |
| `LSHIndex` | `com.raditha.dedup.lsh.LSHIndex` | Banding index; bit-packed `long` bucket keys (8-bit band index + 56-bit segment hash) |
| `findCandidatesLSH` | `DuplicationAnalyzer` | Fused query-and-add loop; lazy AST normalization only for pairs that collide |
| `findCandidatesBruteForce` | `DuplicationAnalyzer` | Exhaustive pairwise comparison; retained as fallback and as the recall baseline in tests |

Pre-filters (`PreFilterChain`: size/structural) and `ASTSimilarityCalculator` run *after* LSH
candidate generation, so LSH only decides *which* pairs are verified, never whether a verified
pair is reported.

### 2.2 Default parameters
`num_bands = 25`, `rows_per_band = 4` (signature length 100). The banding S-curve
$P(\text{collide}) = 1-(1-s^{r})^{b}$ gives $\approx 0.99$ collision probability at Jaccard
$s=0.6$, $\approx 0.55$ at $s=0.4$ and $\approx 0.18$ at $s=0.3$, i.e. pairs well below the
similarity threshold are pruned while near-duplicates are essentially always proposed.

## 3. Recall Validation

`RecallVerificationTest` (`src/test/java/com/raditha/dedup/analyzer/`) treats
`findCandidatesBruteForce` as ground truth and measures how many above-threshold pairs
`findCandidatesLSH` also proposes:

*   `lshKeepsEveryBruteForceDuplicateWithDefaultSettings` asserts **recall = 1.0** on a fixture
    of planted near-duplicate groups (renamed identifiers, different literals) mixed with
    unrelated noise methods.
*   `parameterSweep` prints a table of `recall`, `lshCandidates` and `bruteCandidates` for a grid
    of `num_bands x rows_per_band` (e.g. 5x4 ... 50x2, 10x10, 5x20). Only very strict banding
    (5 bands x 20 rows) starts to lose recall (~0.8); every configuration with $r \le 10$ keeps
    recall at 1.0 on the fixture.

Observation from the sweep: on small, duplicate-dense inputs the `PreFilterChain` already
removes most non-candidates, so LSH and brute force verify a similar number of pairs. The
$O(N) $ benefit materialises on large, sparse projects where the vast majority of the
$N(N-1)/2$ pairs never share a bucket.

## 4. Memory Profile of `LSHIndex`

`LSHIndex` keeps a single `HashMap<Long, List<StatementSequence>>`:

*   **Entries**: at most `N * num_bands` bucket memberships (one per band per sequence). With
    defaults this is `25 N` list slots. Sequences are stored by reference, not copied.
*   **Per bucket**: boxed `Long` key (~16 B) + `HashMap.Node` (~32 B) + `ArrayList` header
    (~40 B) + 4-8 B per member reference. For $N = 40{,}000$ sequences and 25 bands this is
    roughly `1M` memberships → on the order of **40-80 MB** worst case (every membership in its
    own bucket), far less when duplicates share buckets.
*   **Signatures are transient**: `computeSignature` returns an `int[numHashes]` that is used to
    derive bucket keys and then dropped; signatures are *not* retained, so memory does not scale
    with `rows_per_band` beyond the transient array.
*   **Bucket keys are primitives packed into a `long`** (no `String` keys), which was the main
    allocation hotspot in the first prototype.
*   **Knobs**: reducing `num_bands` reduces memory linearly (and recall); increasing
    `rows_per_band` costs nothing in retained memory but lowers recall. For very large inputs
    consider sharding the index per package/module, or replacing the boxed `HashMap` with a
    primitive-keyed map (e.g. an open-addressing `long -> int[]` table) if profiling shows
    `HashMap.Node`/`Long` dominating the heap.
*   **How to profile**: run `./run-bertie.sh analyze` under
    `-XX:+HeapDumpOnOutOfMemoryError` / `-Xlog:gc` or attach JFR
    (`-XX:StartFlightRecording=duration=120s,filename=lsh.jfr`) and inspect allocation by class;
    `LSHIndex`, `java.util.HashMap$Node`, `java.lang.Long` and `java.util.ArrayList` are the
    classes to watch. The `ScalabilityIntegrationTest` generates 50k synthetic sequences and is a
    convenient harness for this.

## 5. Remaining Work

*   Parameter tuning on real large repositories (the sweep above uses a synthetic fixture).
*   Optional primitive-keyed bucket map if heap profiling on 100k+ sequences shows pressure.
*   Progress reporting for the indexing step in the CLI.
