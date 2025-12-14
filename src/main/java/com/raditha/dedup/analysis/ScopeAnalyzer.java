package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Analyzes variable scope to determine what variables are available
 * for use when extracting duplicate code into a method.
 */
public class ScopeAnalyzer {

    /**
     * Get all variables available at the start of a statement sequence.
     * 
     * @param sequence Statement sequence to analyze
     * @return List of available variables with their types
     */
    public List<VariableInfo> getAvailableVariables(StatementSequence sequence) {
        List<VariableInfo> available = new ArrayList<>();

        // 1. Method parameters
        available.addAll(extractMethodParameters(sequence));

        // 2. Class fields
        available.addAll(extractClassFields(sequence));

        // 3. Local variables declared before the sequence
        available.addAll(extractLocalVariables(sequence));

        return available;
    }

    /**
     * Extract method parameters.
     */
    private List<VariableInfo> extractMethodParameters(StatementSequence sequence) {
        List<VariableInfo> params = new ArrayList<>();

        MethodDeclaration method = sequence.containingMethod();
        if (method == null) {
            return params;
        }

        for (Parameter param : method.getParameters()) {
            params.add(new VariableInfo(
                    param.getNameAsString(),
                    param.getTypeAsString(),
                    true, // isParameter
                    false, // isField
                    param.isFinal()));
        }

        return params;
    }

    /**
     * Extract class fields accessible from this method.
     */
    private List<VariableInfo> extractClassFields(StatementSequence sequence) {
        List<VariableInfo> fields = new ArrayList<>();

        if (sequence.compilationUnit() == null) {
            return fields;
        }

        // Find the containing class
        Optional<ClassOrInterfaceDeclaration> containingClass = sequence.compilationUnit()
                .findFirst(ClassOrInterfaceDeclaration.class);

        if (containingClass.isEmpty()) {
            return fields;
        }

        // Extract all fields
        for (FieldDeclaration field : containingClass.get().getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                fields.add(new VariableInfo(
                        var.getNameAsString(),
                        var.getTypeAsString(),
                        false, // isParameter
                        true, // isField
                        field.isFinal()));
            }
        }

        return fields;
    }

    /**
     * Extract local variables declared before the sequence starts.
     * This is a simplified implementation - a full version would need
     * to traverse the AST to find all variables in scope.
     */
    private List<VariableInfo> extractLocalVariables(StatementSequence sequence) {
        List<VariableInfo> locals = new ArrayList<>();

        MethodDeclaration method = sequence.containingMethod();
        if (method == null || !method.getBody().isPresent()) {
            return locals;
        }

        List<Statement> allStatements = method.getBody().get().getStatements();
        List<Statement> sequenceStatements = sequence.statements();

        if (sequenceStatements.isEmpty()) {
            return locals;
        }

        // Find the index of the first statement in our sequence
        Statement firstSeqStmt = sequenceStatements.get(0);
        int sequenceStart = allStatements.indexOf(firstSeqStmt);

        if (sequenceStart <= 0) {
            return locals; // No statements before the sequence
        }

        // Look at statements before the sequence for variable declarations
        for (int i = 0; i < sequenceStart; i++) {
            Statement stmt = allStatements.get(i);

            // Find variable declarations in this statement
            stmt.findAll(VariableDeclarator.class).forEach(var -> {
                locals.add(new VariableInfo(
                        var.getNameAsString(),
                        var.getTypeAsString(),
                        false, // isParameter
                        false, // isField
                        false // Unknown if final for local vars in this simplified version
                ));
            });
        }

        return locals;
    }

    /**
     * Information about a variable in scope.
     */
    public record VariableInfo(
            String name,
            String type,
            boolean isParameter,
            boolean isField,
            boolean isFinal) {
        /**
         * Check if this is a local variable.
         */
        public boolean isLocal() {
            return !isParameter && !isField;
        }

        @Override
        public String toString() {
            String kind = isParameter ? "param" : isField ? "field" : "local";
            String finalMod = isFinal ? " final" : "";
            return String.format("%s %s %s%s", kind, type, name, finalMod);
        }
    }
}
