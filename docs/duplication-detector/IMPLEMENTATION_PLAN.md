# Duplication Detector - Implementation Plan

**Last Updated**: January 4, 2026
**Status**: Core Logic Complete | Optimization & Hardening in Progress

---

## 📅 Roadmap Overview

| Phase | Focus | Status |
|-------|-------|--------|
| **1-10** | **Core Detection & Refactoring** | ✅ **COMPLETE** |
| **11** | **Scalability Optimization (LSH)** | ✅ **Implemented & wired in** (tuning/validation ongoing, see 11.3) |
| **12** | **Functional Reliability (P0 Fixes)** | 🚧 **In Progress** |
| **13** | **Production Release** | 📋 **Planned** |

---

## ✅ Phases 1-10: Foundation (Complete)

*Refer to legacy `implementation_task_list.md` for detailed history.*

*   **Detection**: AST-based sliding window extraction, Hybrid Similarity (LCS/Levenshtein/Structural).
*   **Refactoring**: 4 strategies (Extract Method, @BeforeEach, @ParameterizedTest, Utility Class).
*   **Reporting**: JSON/Text/CSV output with confidence scoring.
*   **CLI**: Integrated CLI with `analyze` and `refactor` modes.

---

## ✅ Phase 11: Scalability Optimization (LSH)

**Goal**: Replace $O(N^2)$ pairwise comparison with $O(N)$ Locality Sensitive Hashing to enable enterprise-scale analysis.

**Status**: The LSH path is implemented and is the **default** candidate generator.
`DuplicationAnalyzer.findCandidates` calls `findCandidatesLSH` when `enable_lsh` is true
(default) and falls back to the exhaustive `findCandidatesBruteForce` only when it is
explicitly disabled. See `docs/duplication-detector/SCALABILITY_ANALYSIS.md` for the
architecture, recall measurements and memory notes.

### 11.1 LSH Infrastructure
- [x] **Implement MinHash**: `com.raditha.dedup.lsh.MinHash` generates signatures from k-shingles (k=3) of `FuzzyTokenizer` tokens.
- [x] **Implement LSH Index**: `com.raditha.dedup.lsh.LSHIndex` uses the banding technique with bit-packed `long` bucket keys.
- [x] **Add Unit Tests**: `MinHashTest`, `LSHIndexTest` (exact match, no match, near match, threshold).

### 11.2 Integration
- [x] **Update DuplicationAnalyzer**: `findCandidatesLSH` replaces the nested loop (fused query-and-add, lazy normalization of colliding pairs only).
- [x] **Pipeline Update**: `PreFilterChain` and `ASTSimilarityCalculator` run *after* LSH candidate generation.
- [x] **Add Integration Tests**:
    - [x] `ScalabilityIntegrationTest`: 50k generated sequences.
    - [x] `RecallVerificationTest`: LSH vs `findCandidatesBruteForce` baseline; asserts recall = 1.0 with default settings on planted duplicates.

### 11.3 Tuning & Benchmarking (remaining)
- [x] **Parameter Sweep (harness)**: `RecallVerificationTest.parameterSweep` reports recall, LSH candidate count and brute-force candidate count over a `num_bands x rows_per_band` grid.
- [ ] **Parameter Sweep (real data)**: Run the sweep against large real repositories (test-bed is small and duplicate-dense) and pick defaults targeting >95% recall for Jaccard > 0.5 with minimal candidates. Current defaults: `num_bands=25`, `rows_per_band=4`.
- [ ] **Benchmark Harness CLI**:
    - **Command**: `java -jar bertie.jar benchmark --mode lsh --input /path/to/large/repo`
    - **Metrics**: indexing time, candidate pair count, candidate/total ratio, recall vs brute force.
- [ ] **Memory Profiling on 100k+ sequences**: verify heap of `LSHIndex` buckets (see memory notes in `SCALABILITY_ANALYSIS.md` §4); consider a primitive-keyed bucket map if `HashMap$Node`/`Long` dominate.

---

## 📋 Phase 12: Functional Reliability (P0 Fixes)

**Goal**: Address known functional equivalence gaps preventing safe auto-refactoring.

### 12.1 Refactoring Correctness
- [x] **Fix Argument Extraction**: Parameters bind to the innermost expression at the recorded source position (`AbstractExtractor.findNodeForParameter`); covered by `RefactoringBehavioralEquivalenceTest` (compiles before/after and asserts identical runtime output).
- [x] **Fix Return Value Detection**: `DataFlowAnalyzer.isTypeCompatible` hardened; multi-live-out relaxation covered by behavioural equivalence tests.
- [x] **Fix Literal Normalization**: String/numeric literals parameterized per occurrence (behavioural equivalence tests).

### 12.2 Type Inference
- [x] **Enhance TypeAnalyzer**: `AbstractResolver` source-level fallback for chained method calls (`AbstractResolverTypeInferenceTest`).
- [x] **Fix Generic Types**: Recursive generic conversion, diamond inference from declarations, erasure-aware `ASTVariationAnalyzer.findLCA` (`CommonSupertypeTest`).

### 12.3 Safety Validation
- [x] **Side-effect detection**: `SafetyValidator.hasDifferentSideEffects` blocks clusters whose duplicates differ in I/O, network, DB, external API or non-idempotent calls (`SideEffectAnalyzer`).
- [x] **Control-flow variation**: `ControlFlowVariationAnalyzer` distinguishes unsafe (structural) from parameterizable (condition-only) variation; the latter can be downgraded to a warning via `allow_parameterizable_control_flow` (default off).

---

## 📋 Phase 13: Production Hardening

**Goal**: Prepare for General Availability (GA).

### 13.1 Testing & Verification
- [ ] **Regression Suite**: Ensure no regressions in detection quality after LSH integration.
- [x] **False Negative Analysis (synthetic)**: `RecallVerificationTest` shows no lost duplicates vs brute force with default banding.
- [ ] **False Negative Analysis (real data)**: Repeat on large repositories.
- [ ] **Memory Profiling**: Ensure `LSHIndex` doesn't consume excessive heap for large projects (notes in `SCALABILITY_ANALYSIS.md` §4).

### 13.2 Documentation & UX
- [ ] **Update User Guide**: Document performance characteristics.
- [ ] **Progress Reporting**: Add progress bar for LSH indexing step in CLI.

---

## Success Criteria

1.  **Scalability**: Analyze 50k lines of code in < 5 minutes.
2.  **Accuracy**: Precision > 90% (few false positives), Recall > 80% (finds most duplicates).
3.  **Safety**: Automated refactorings compile and pass tests 100% of the time.
