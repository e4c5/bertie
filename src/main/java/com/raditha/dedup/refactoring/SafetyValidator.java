package com.raditha.dedup.refactoring;

import com.raditha.dedup.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates safety of refactor operations before applying them.
 * Checks for conflicts, scope issues, and incompatibilities.
 */
public class SafetyValidator {

    /**
     * Validate a refactoring is safe to apply.
     */
    public ValidationResult validate(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        List<ValidationIssue> issues = new ArrayList<>();

        // 1. Check for method name conflicts
        if (hasMethodNameConflict(cluster, recommendation)) {
            issues.add(ValidationIssue.error(
                    "Method name '" + recommendation.suggestedMethodName() + "' already exists in class"));
        }

        // 2. Check for variable scope issues
        if (hasVariableScopeIssues(cluster)) {
            issues.add(ValidationIssue.error(
                    "Variables used are not in scope for extraction"));
        }

        // 3. Check for annotation incompatibilities
        if (hasIncompatibleAnnotations(cluster)) {
            issues.add(ValidationIssue.warning(
                    "Methods have different annotations (@Transactional, @Async, etc.)"));
        }

        // 4. Check for control flow differences
        SimilarityResult similarity = cluster.duplicates().isEmpty() ? null : cluster.duplicates().get(0).similarity();
        if (similarity != null && similarity.variations().hasControlFlowDifferences()) {
            issues.add(ValidationIssue.error(
                    "Control flow differs between duplicates - cannot safely refactor"));
        }

        // 5. Check parameter count
        if (recommendation.suggestedParameters().size() > 5) {
            issues.add(ValidationIssue.warning(
                    "More than 5 parameters (" + recommendation.suggestedParameters().size() +
                            ") - consider refactoring to use a parameter object"));
        }

        return new ValidationResult(issues);
    }

    /**
     * Check if the suggested method name conflicts with existing methods.
     */
    private boolean hasMethodNameConflict(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        StatementSequence primary = cluster.primary();
        var containingClass = primary.containingMethod() != null ? primary.containingMethod().findAncestor(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElse(null) : null;

        if (containingClass == null) {
            return false;
        }

        // Check if method with same name exists
        return containingClass.getMethods().stream()
                .anyMatch(m -> m.getNameAsString().equals(recommendation.suggestedMethodName()));
    }

    /**
     * Check for variable scope issues.
     */
    private boolean hasVariableScopeIssues(DuplicateCluster cluster) {
        // For now, assume scope is valid
        // Full implementation would analyze variable declarations and usage
        return false;
    }

    /**
     * Check for annotation incompatibilities.
     */
    private boolean hasIncompatibleAnnotations(DuplicateCluster cluster) {
        // Check if duplicate methods have different annotations
        // For now, simplified check
        return false;
    }

    /**
     * Result of validation check.
     */
    public record ValidationResult(List<ValidationIssue> issues) {
        public boolean isValid() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        }

        public boolean hasWarnings() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.WARNING);
        }

        public List<String> getErrors() {
            return issues.stream()
                    .filter(i -> i.severity() == Severity.ERROR)
                    .map(ValidationIssue::message)
                    .toList();
        }

        public List<String> getWarnings() {
            return issues.stream()
                    .filter(i -> i.severity() == Severity.WARNING)
                    .map(ValidationIssue::message)
                    .toList();
        }
    }

    /**
     * A single validation issue.
     */
    public record ValidationIssue(Severity severity, String message) {
        public static ValidationIssue error(String message) {
            return new ValidationIssue(Severity.ERROR, message);
        }

        public static ValidationIssue warning(String message) {
            return new ValidationIssue(Severity.WARNING, message);
        }
    }

    /**
     * Severity of validation issue.
     */
    public enum Severity {
        ERROR, // Blocks refactoring
        WARNING // Proceeds with caution
    }
}
