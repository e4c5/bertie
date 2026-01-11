# Duplication Detector - Implementation Plan

**Last Updated**: January 4, 2026
**Status**: Core Logic Complete | Optimization & Hardening in Progress

---

## 📅 Roadmap Overview

| Phase | Focus | Status |
|-------|-------|--------|
| **1-10** | **Core Detection & Refactoring** | ✅ **COMPLETE** |
| **11** | **Scalability Optimization (LSH)** | 🚧 **Ready for Implementation** |
| **12** | **Functional Reliability (P0 Fixes)** | 📋 **Planned** |
| **13** | **Production Release** | 📋 **Planned** |

---

## ✅ Phases 1-10: Foundation (Complete)

*Refer to legacy `implementation_task_list.md` for detailed history.*

*   **Detection**: AST-based sliding window extraction, Hybrid Similarity (LCS/Levenshtein/Structural).
*   **Refactoring**: 4 strategies (Extract Method, @BeforeEach, @ParameterizedTest, Utility Class).
*   **Reporting**: JSON/Text/CSV output with confidence scoring.
*   **CLI**: Integrated CLI with `analyze` and `refactor` modes.

---

## 🚧 Phase 11: Scalability Optimization (LSH)

**Goal**: Replace $O(N^2)$ pairwise comparison with $O(N)$ Locality Sensitive Hashing to enable enterprise-scale analysis.

### 11.1 LSH Infrastructure
- [ ] **Implement MinHash**: Create `MinHash` class to generate signatures from token sequences (k-shingles).
- [ ] **Implement LSH Index**: Create `LSHIndex` class using Banding technique (bands/rows configuration).
- [ ] **Add Unit Tests**:
    - [ ] `MinHashTest`: Verify deterministic signatures for identical sequences.
    - [ ] `LSHIndexTest`:
        - [ ] `testExactMatch`: Identical sequences must collide.
        - [ ] `testNoMatch`: Completely distinct sequences (Jaccard=0) must NOT collide.
        - [ ] `testNearMatch`: Sequences with high Jaccard (>0.8) should collide with high probability.
        - [ ] `testThreshold`: Verify cutoff behavior (approximate).

### 11.2 Integration
- [ ] **Update DuplicationAnalyzer**: Replace nested loop with `LSHIndex` candidate generation.
- [ ] **Pipeline Update**: Ensure Pre-Filters (Size/Structural) run *after* LSH candidate generation to verify matches.
- [ ] **Add Integration Tests**:
    - [ ] `ScalabilityIntegrationTest`: Run on 50k generated sequences, assert time < 5s.
    - [ ] `RecallVerificationTest`: Ensure known duplicates in the `test-bed` submodule (and `commons-lang` sample) are still found.

### 11.3 Tuning & Benchmarking
- [ ] **Parameter Sweep**:
    - Vary `numHashFunctions` (e.g., 64, 100, 128, 256).
    - Vary `numBands` (e.g., 10, 20, 25, 50).
    - **Target**: Find configuration yielding >95% recall for Jaccard > 0.5 with minimal candidates.
- [ ] **Benchmark Harness**:
    - **Command**: `java -jar bertie.jar benchmark --mode lsh --input /path/to/large/repo`
    - **Metrics**:
        - Indexing Time (ms)
        - Candidate Pair Count
        - Candidate/Total Ratio (Filtering Power)
        - Recall (vs Brute Force baseline)

---

## 📋 Phase 12: Functional Reliability (P0 Fixes)

**Goal**: Address known functional equivalence gaps preventing safe auto-refactoring.

### 12.1 Refactoring Correctness
- [ ] **Fix Argument Extraction**: Ensure extracted parameters bind to correct values (fix `ArgumentExtraction` logic).
- [ ] **Fix Return Value Detection**: Improve live variable analysis to correctly identify required return values.
- [ ] **Fix Literal Normalization**: Ensure string literals are correctly matched/parameterized.

### 12.2 Type Inference
- [ ] **Enhance TypeAnalyzer**: Support complex expression type inference (e.g., chained method calls).
- [ ] **Fix Generic Types**: Better handling of `List<T>` and diamond operators during extraction.

---

## 📋 Phase 13: Production Hardening

**Goal**: Prepare for General Availability (GA).

### 13.1 Testing & Verification
- [ ] **Regression Suite**: Ensure no regressions in detection quality after LSH integration.
- [ ] **False Positive Analysis**: Validate LSH doesn't introduce excessive false negatives (missed duplicates).
- [ ] **Memory Profiling**: Ensure `LSHIndex` doesn't consume excessive heap for large projects.

### 13.2 Documentation & UX
- [ ] **Update User Guide**: Document performance characteristics.
- [ ] **Progress Reporting**: Add progress bar for LSH indexing step in CLI.

---

## Success Criteria

1.  **Scalability**: Analyze 50k lines of code in < 5 minutes.
2.  **Accuracy**: Precision > 90% (few false positives), Recall > 80% (finds most duplicates).
3.  **Safety**: Automated refactorings compile and pass tests 100% of the time.
