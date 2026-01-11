# LSH Implementation Analysis Report

**Date**: January 11, 2026
**Subject**: Validation of LSH Implementation against Design Specification

## Executive Summary
A review of the current LSH implementation reveals a critical deviation from the design specification regarding **Token Normalization**. The current implementation of `ASTNormalizer` aggressively anonymizes method names during fuzzy normalization, whereas the design document explicitly requires method names to be preserved to maintain semantic meaning.

## 1. Design Specification Requirement
According to `docs/duplication-detector/duplication_detector_design.md` (Section 4: Token Normalization Strategy), the design explicitly distinguishes between cosmetic differences (variables, literals) and semantic meaning (method names).

**Design Quote:**
> **What We Preserve (Semantic Meaning)**
> | Code | Token | Rationale |
> |------|-------|-----------|
> | `userRepo.save(user)` | `METHOD_CALL(save)` | **Method name is semantic** |
> | `user.setActive(true)` | `METHOD_CALL(setActive)` | Different from `setDeleted`! |

> **Critical Design Decision**
> **❌ WRONG** (Over-normalization):
> `user.setActive(true);   → [VAR, CALL, LITERAL]`
> `user.setDeleted(true);  → [VAR, CALL, LITERAL]`
> // These would match 100% but are SEMANTICALLY DIFFERENT!

## 2. Current Implementation Status
The `ASTNormalizer.java` class implements fuzzy normalization in the `NormalizingVisitor` class. Specifically, the visiting of `MethodCallExpr` nodes behaves as follows:

```java
// src/main/java/com/raditha/dedup/normalization/ASTNormalizer.java

@Override
public Visitable visit(MethodCallExpr n, Void arg) {
    // Visit scope/args first, then set name if enabled
    super.visit(n, arg);
    if (includeIdentifiers) {
        n.setName("METHOD"); // <--- DEVIATION HERE
    }
    return n;
}
```

When `normalizeFuzzy` is called (which sets `includeIdentifiers = true`), **all method names are replaced with the string "METHOD"**.

## 3. Impact Analysis

### 3.1 Loss of Semantic Discrimination
By replacing all method names with "METHOD", the LSH signatures for semantically distinct operations become identical if their structure is the same.

**Example:**
*   **Source A**: `account.deposit(amount);`
*   **Source B**: `account.withdraw(amount);`

**Current Implementation produces:**
*   A: `VAR.METHOD(VAR);`
*   B: `VAR.METHOD(VAR);`
*   **Result**: 100% similarity signature. LSH will bucket these together.

**Design Intent required:**
*   A: `VAR.deposit(VAR);`
*   B: `VAR.withdraw(VAR);`
*   **Result**: Distinct signatures.

### 3.2 False Positive Candidates
While LSH is intended to be a candidate generator (high recall), this level of over-normalization will result in an excessive number of false positive candidates being passed to the detailed comparison stage. This will degrade the performance benefits of LSH by forcing the expensive $O(N^2)$ comparator to filter out pairs that should have been distinct in the LSH index.

### 3.3 Refactoring Safety
The design relies on semantic method names to distinguish between duplications that are safe to refactor. Merging `setActive` and `setDeleted` into a cluster would be dangerous if not caught by the secondary filters.

## 4. Recommendation
The `ASTNormalizer` class must be modified to align with the design document.
1.  **Modify `NormalizingVisitor`**: The `visit(MethodCallExpr)` method should **not** replace the method name when `includeIdentifiers` is true. It should only normalize the scope (variable) and arguments.
2.  **Verify LSH Logic**: Ensure `LSHIndex` still functions correctly with the increased vocabulary size of method names (it should, as MinHash handles arbitrary string tokens).

## 5. Conclusion
The current LSH implementation is **incorrect** with respect to the design specification. The normalization logic must be patched to preserve method names before the feature can be considered valid.
