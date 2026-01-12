# Code Review for Bertie

## Executive Summary
Bertie is a comprehensive duplicate code detection and refactoring tool. The codebase is well-structured, modular, and makes good use of JavaParser for AST manipulation. The recent addition of LSH (Locality Sensitive Hashing) for scalability is a significant improvement, although it currently has some efficiency bottlenecks. The refactoring strategies are robust and include safety checks, which is commendable.

## Detailed Findings

### 1. Detection Engine (Core Logic)

#### LSH Implementation (`com.raditha.dedup.lsh`)
*   **Efficiency**:
    *   `LSHIndex` uses a String key for buckets (`bandIndex:hash1,hash2...`). This involves significant String concatenation and memory allocation. **Recommendation**: Use a custom `BucketKey` record/object that holds the band index and hash array to reduce memory pressure.
    *   `MinHash.computeSignature` generates shingles as strings and then hashes them. This creates many temporary String objects. **Recommendation**: Compute rolling hashes or hash tokens directly without intermediate String creation.
*   **Correctness**: The implementation of MinHash and Banding seems correct and follows standard algorithms.

#### Similarity Calculation (`com.raditha.dedup.similarity`)
*   **Performance Bottleneck**: `ASTLCSSimilarity` and `ASTLevenshteinSimilarity` use `nodesMatch` which calls `NormalizedNode.structurallyEquals`. This method compares `normalized.toString()`. Since this happens inside the O(N*M) loop of the DP algorithms, it is extremely expensive. **Recommendation**: Cache the hash or string representation of `NormalizedNode`, or implement a direct AST comparison that doesn't rely on string conversion.

#### Normalization (`com.raditha.dedup.normalization`)
*   **Compliance**: `ASTNormalizer` correctly implements the requirement to preserve method names while anonymizing variables (`VAR`) and fields (`FIELD`). This ensures that semantic meaning of method calls is retained during structural comparison.

### 2. Refactoring Engine

#### Refactorer Implementation (`com.raditha.dedup.refactoring`)
*   **Robustness**: The refactoring engine is impressive. It handles:
    *   **Live-out variables**: Correctly identifies variables that need to be returned from extracted methods (`DataFlowAnalyzer`).
    *   **External dependencies**: Detects if a sequence depends on variables not available in the new scope.
    *   **Type Resolution**: Uses `Antikythera` for robust type resolution.
*   **Safety**: `SafetyValidator` provides a good layer of protection against invalid refactorings.
*   **Feedback**: `RefactoringEngine` writes directly to `System.out`. **Recommendation**: Use a proper logging framework (SLF4J) or a dedicated `UserInterface` abstraction to separate logic from I/O.

#### Extraction Logic (`com.raditha.dedup.extraction`)
*   **StatementExtractor**: The sliding window approach is flexible. The `calculateOffset` method uses a rough approximation (`line * 80`), which seems risky if used for precise text manipulation, though `StatementSequence` relies on `Range` (line/column) which is accurate.

### 3. Analysis Utilities

#### Data Flow Analysis (`com.raditha.dedup.analysis`)
*   **Correctness**: `DataFlowAnalyzer` correctly finds defined and used variables.
*   **Improvement**: `findCandidates` modifies a list passed as an argument. Returning a new list or stream would be cleaner.

### 4. Tests
*   **Scalability Test**: `ScalabilityIntegrationTest` was previously passing trivially because it generated code that didn't meet the minimum size criteria, resulting in zero comparisons.
    *   **Fix Applied**: I updated the test to generate method bodies larger than 5 lines.
    *   **Result**: The test now correctly exercises the detection engine. LSH performance was verified (passed with 500 methods).

## Conclusion
The project is in a very good state. The primary area for improvement is performance optimization in the detection phase (specifically LSH string allocations and LCS/Levenshtein string comparisons). The refactoring logic is mature and handles complex edge cases well.
