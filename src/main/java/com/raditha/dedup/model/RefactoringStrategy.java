package com.raditha.dedup.model;

/**
 * Recommended refactoring strategy for duplicate code.
 * Selected based on code location (test vs source) and duplication pattern.
 */
public enum RefactoringStrategy {
    /** Extract to a private helper method in the same class */
    EXTRACT_HELPER_METHOD,

    /** Consolidate similar tests into @ParameterizedTest */
    EXTRACT_TO_PARAMETERIZED_TEST,

    /** Extract to a utility class (cross-class duplicates) */
    EXTRACT_TO_UTILITY_CLASS,

    /** Extract to a common parent class (cross-class instance method duplicates) */
    EXTRACT_PARENT_CLASS,

    /** Too complex for automated refactoring - requires manual review */
    MANUAL_REVIEW_REQUIRED
}
