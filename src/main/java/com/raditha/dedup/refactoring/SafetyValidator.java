package com.raditha.dedup.refactoring;

import com.raditha.dedup.analysis.EscapeAnalyzer;
import com.raditha.dedup.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
                    "Method name '" + recommendation.getSuggestedMethodName() + "' already exists in class"));
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
        if (recommendation.getSuggestedParameters().size() > 5) {
            issues.add(ValidationIssue.warning(
                    "More than 5 parameters (" + recommendation.getSuggestedParameters().size() +
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
                .anyMatch(m -> m.getNameAsString().equals(recommendation.getSuggestedMethodName()));
    }

    /**
     * Check for variable scope issues.
     * 
     * FIXED Gap 8: Now uses EscapeAnalyzer to detect variable capture.
     * 
     * Returns true if the sequence modifies LOCAL variables from outer scope,
     * which would break when extracted to a separate method.
     * 
     * Class fields are allowed to be modified (common in test setup code).
     */
    private boolean hasVariableScopeIssues(DuplicateCluster cluster) {
        EscapeAnalyzer analyzer = new EscapeAnalyzer();

        // Get class field names from the primary sequence
        Set<String> classFields = getClassFieldNames(cluster.primary());

        // Check primary sequence
        Set<String> escapingVars = analyzer.analyze(cluster.primary());
        escapingVars.removeAll(classFields); // Allow class field modifications
        if (!escapingVars.isEmpty()) {
            return true;
        }

        // Check all duplicate sequences
        for (SimilarityPair pair : cluster.duplicates()) {
            Set<String> escapingVars1 = analyzer.analyze(pair.seq1());
            escapingVars1.removeAll(classFields);
            if (!escapingVars1.isEmpty()) {
                return true;
            }
            
            Set<String> escapingVars2 = analyzer.analyze(pair.seq2());
            escapingVars2.removeAll(classFields);
            if (!escapingVars2.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get all field names from the containing class of a sequence.
     */
    private Set<String> getClassFieldNames(StatementSequence sequence) {
        var method = sequence.containingMethod();
        if (method == null) {
            return java.util.Collections.emptySet();
        }
        
        var clazz = method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .orElse(null);
        if (clazz == null) {
            return java.util.Collections.emptySet();
        }
        
        Set<String> fieldNames = new java.util.HashSet<>();
        clazz.getFields().forEach(field -> 
            field.getVariables().forEach(vd ->
                fieldNames.add(vd.getNameAsString())
            )
        );
        return fieldNames;
    }

    /**
     * Check for annotation incompatibilities.
     * 
     * Verifies that methods containing duplicate sequences have compatible annotations.
     * Certain annotations like @Transactional, @Async, @Cacheable affect method behavior
     * and should be consistent across all duplicates.
     */
    private boolean hasIncompatibleAnnotations(DuplicateCluster cluster) {
        // Annotations that affect method behavior and must be consistent
        Set<String> criticalAnnotations = Set.of(
            "Transactional",
            "Async", 
            "Cacheable",
            "Scheduled",
            "Retryable",
            "Timed"
        );
        
        // Get annotations from primary sequence's containing method
        Set<String> primaryAnnotations = getMethodAnnotations(cluster.primary());
        
        // Check all duplicate pairs
        for (SimilarityPair pair : cluster.duplicates()) {
            Set<String> seq1Annotations = getMethodAnnotations(pair.seq1());
            Set<String> seq2Annotations = getMethodAnnotations(pair.seq2());
            
            // Check if critical annotations differ
            if (hasCriticalAnnotationDifference(primaryAnnotations, seq1Annotations, criticalAnnotations) ||
                hasCriticalAnnotationDifference(primaryAnnotations, seq2Annotations, criticalAnnotations) ||
                hasCriticalAnnotationDifference(seq1Annotations, seq2Annotations, criticalAnnotations)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get all annotation names from a method containing a sequence.
     */
    private Set<String> getMethodAnnotations(StatementSequence sequence) {
        var method = sequence.containingMethod();
        if (method == null) {
            return java.util.Collections.emptySet();
        }
        
        return method.getAnnotations().stream()
            .map(annotation -> annotation.getNameAsString())
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Check if two annotation sets differ in critical annotations.
     */
    private boolean hasCriticalAnnotationDifference(Set<String> annotations1, 
                                                    Set<String> annotations2, 
                                                    Set<String> criticalAnnotations) {
        for (String critical : criticalAnnotations) {
            boolean has1 = annotations1.contains(critical);
            boolean has2 = annotations2.contains(critical);
            
            // If one has it and the other doesn't, that's incompatible
            if (has1 != has2) {
                return true;
            }
        }
        
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
