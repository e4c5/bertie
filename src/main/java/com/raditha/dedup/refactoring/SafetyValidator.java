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

        // 1. Check for method name conflicts (only for same-class extractions)
        if (shouldCheckMethodNameConflict(recommendation) && hasMethodNameConflict(cluster, recommendation)) {
            issues.add(ValidationIssue.error(
                    "Method name '" + recommendation.getSuggestedMethodName() + "' already exists in class"));
        }

        // 2. Check for variable scope issues
        if (hasVariableScopeIssues(cluster)) {
            issues.add(ValidationIssue.error(
                    "Variables used are not in scope for extraction"));
        }

        // 3. Check for control flow differences
        SimilarityResult similarity = cluster.duplicates().isEmpty() ? null : cluster.duplicates().get(0).similarity();
        if (similarity != null && similarity.variations().hasControlFlowDifferences()) {
            issues.add(ValidationIssue.error(
                    "Control flow differs between duplicates - cannot safely refactor"));
        }

        // 4. Check parameter count
        if (recommendation.getSuggestedParameters().size() > 5) {
            issues.add(ValidationIssue.warning(
                    "More than 5 parameters (" + recommendation.getSuggestedParameters().size() +
                            ") - consider refactoring to use a parameter object"));
        }

        // 5. Check for final field assignments (not allowed in extracted methods)
        if (recommendation.getStrategy() != RefactoringStrategy.CONSTRUCTOR_DELEGATION && hasFinalFieldAssignments(cluster.primary())) {
            issues.add(ValidationIssue.error(
                    "Cannot extract code that assigns to final fields into a separate method"));
        }

        // 6. Check for nested type extraction
        if (hasNestedTypeIssue(cluster, recommendation)) {
            issues.add(ValidationIssue.error(
                    "Cannot refactor code from nested types (Enums, Inner Classes) using this strategy"));
        }

        return new ValidationResult(issues);
    }

    /**
     * Check if the suggested method name conflicts with existing methods.
     */
    private boolean hasMethodNameConflict(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        StatementSequence primary = cluster.primary();
        var containingClass = primary.containingCallable() != null ? primary.containingCallable().findAncestor(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElse(null) : null;

        if (containingClass == null) {
            return false;
        }

        // Check if method with same name exists
        return containingClass.getMethods().stream()
                .anyMatch(m -> m.getNameAsString().equals(recommendation.getSuggestedMethodName()));
    }

    private boolean shouldCheckMethodNameConflict(RefactoringRecommendation recommendation) {
        return switch (recommendation.getStrategy()) {
            case EXTRACT_HELPER_METHOD, EXTRACT_TO_PARAMETERIZED_TEST -> true;
            case EXTRACT_TO_UTILITY_CLASS, EXTRACT_PARENT_CLASS, CONSTRUCTOR_DELEGATION, MANUAL_REVIEW_REQUIRED -> false;
        };
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
        var method = sequence.containingCallable();
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

    private boolean hasFinalFieldAssignments(StatementSequence sequence) {
        var method = sequence.containingCallable();
        if (method == null) return false;
        var clazz = method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElse(null);
        if (clazz == null) return false;

        Set<String> finalFields = collectFinalFieldNames(clazz);

        if (finalFields.isEmpty()) return false;

        // Collect local variables and parameters to avoid false positives from shadowing
        Set<String> localsAndParams = collectLocalAndParameterNames(method);

        String className = clazz.getNameAsString();

        for (com.github.javaparser.ast.stmt.Statement stmt : sequence.statements()) {
            List<com.github.javaparser.ast.expr.AssignExpr> assignments = stmt.findAll(com.github.javaparser.ast.expr.AssignExpr.class);
            for (com.github.javaparser.ast.expr.AssignExpr assign : assignments) {
                if (isFinalFieldAssignment(assign.getTarget(), finalFields, localsAndParams, className)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> collectFinalFieldNames(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        Set<String> finalFields = new java.util.HashSet<>();
        clazz.getFields().stream()
                .filter(com.github.javaparser.ast.body.FieldDeclaration::isFinal)
                .forEach(f -> f.getVariables().forEach(v -> finalFields.add(v.getNameAsString())));
        return finalFields;
    }

    private Set<String> collectLocalAndParameterNames(com.github.javaparser.ast.body.CallableDeclaration<?> method) {
        Set<String> localsAndParams = new java.util.HashSet<>();
        method.getParameters().forEach(p -> localsAndParams.add(p.getNameAsString()));
        method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(v -> localsAndParams.add(v.getNameAsString()));
        return localsAndParams;
    }

    private boolean isFinalFieldAssignment(
            com.github.javaparser.ast.expr.Expression targetExpr,
            Set<String> finalFields,
            Set<String> localsAndParams,
            String className) {
        if (targetExpr.isFieldAccessExpr()) {
            var fa = targetExpr.asFieldAccessExpr();
            String fieldName = fa.getNameAsString();
            if (!finalFields.contains(fieldName)) {
                return false;
            }
            // this.field or super.field always refers to class field
            if (fa.getScope().isThisExpr() || fa.getScope().isSuperExpr()) {
                return true;
            }
            // ClassName.field (static access)
            return fa.getScope().isNameExpr()
                    && fa.getScope().asNameExpr().getNameAsString().equals(className);
        }

        if (targetExpr.isNameExpr()) {
            String name = targetExpr.asNameExpr().getNameAsString();
            // Only treat unqualified names as field access if not shadowed
            return finalFields.contains(name) && !localsAndParams.contains(name);
        }

        return false;
    }

    private boolean hasNestedTypeIssue(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        if (recommendation.getStrategy() != RefactoringStrategy.EXTRACT_PARENT_CLASS) {
            return false;
        }

        StatementSequence primary = cluster.primary();
        var callable = primary.containingCallable();
        if (callable == null) return false;

        // Check if inside an enum - unsupported for EXTRACT_PARENT_CLASS
        if (callable.findAncestor(com.github.javaparser.ast.body.EnumDeclaration.class).isPresent()) {
            return true;
        }

        var clazz = callable.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElse(null);
        if (clazz == null) return true; // Not in a class

        // Check if the method is inside an anonymous class or similar within a method
        if (callable.getParentNode().map(p -> p != clazz).orElse(true)) {
            return true;
        }

        // Check if the class itself is nested (inner class)
        return clazz.isNestedType();
    }

    /**
     * Result of validation check.
     */
    public record ValidationResult(List<ValidationIssue> issues) {
        /**
         * Checks if the refactoring is valid (no errors).
         *
         * @return true if valid
         */
        public boolean isValid() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        }

        /**
         * Checks if there are any warnings.
         *
         * @return true if warnings exist
         */
        public boolean hasWarnings() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.WARNING);
        }

        /**
         * Gets list of error messages.
         *
         * @return list of errors
         */
        public List<String> getErrors() {
            return issues.stream()
                    .filter(i -> i.severity() == Severity.ERROR)
                    .map(ValidationIssue::message)
                    .toList();
        }

        /**
         * Gets list of warning messages.
         *
         * @return list of warnings
         */
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
        /**
         * Creates an error issue.
         *
         * @param message The error message
         * @return new ValidationIssue with ERROR severity
         */
        public static ValidationIssue error(String message) {
            return new ValidationIssue(Severity.ERROR, message);
        }

        /**
         * Creates a warning issue.
         *
         * @param message The warning message
         * @return new ValidationIssue with WARNING severity
         */
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
