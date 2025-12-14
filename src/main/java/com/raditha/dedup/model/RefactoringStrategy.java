package com.raditha.dedup.model;

/**
 * Recommended refactoring strategy for duplicate code.
 * Selected based on code location (test vs source) and duplication pattern.
 */
public enum RefactoringStrategy {
    /** Extract to a private helper method in the same class */
    EXTRACT_HELPER_METHOD,

    /** Extract test setup code to @BeforeEach method */
    EXTRACT_TO_BEFORE_EACH,

    /** Consolidate similar tests into @ParameterizedTest */
    EXTRACT_TO_PARAMETERIZED_TEST,

    /** Extract to a utility class (cross-class duplicates) */
    EXTRACT_TO_UTILITY_CLASS,

    /** Too complex for automated refactoring - requires manual review */
    MANUAL_REVIEW_REQUIRED
}
