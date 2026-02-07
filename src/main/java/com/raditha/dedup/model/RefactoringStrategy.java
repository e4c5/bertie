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

    /** Extract anonymous class to a new public top-level class */
    EXTRACT_ANONYMOUS_TO_PUBLIC_CLASS,

    /** Extract anonymous class to an inner class on a common parent */
    EXTRACT_ANONYMOUS_TO_PARENT_INNER_CLASS,

    /** Refactor constructor to delegate to another constructor using this(...) */
    CONSTRUCTOR_DELEGATION,

    /** Too complex for automated refactoring - requires manual review */
    MANUAL_REVIEW_REQUIRED
}
