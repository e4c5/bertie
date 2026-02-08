package com.raditha.dedup.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.ContainerType;
import com.raditha.dedup.model.StatementSequence;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Analyzes anonymous class methods to detect field access from the outer (enclosing) class.
 * This is important for refactoring because:
 * 1. Outer field access requires special handling when extracting to utility methods
 * 2. Instance fields accessed via implicit 'this' need qualification
 * 3. Captured fields may need to be passed as parameters
 */
public class OuterClassFieldAnalyzer {

    /**
     * Find all outer class fields accessed within the given sequence.
     * Only applicable for ANONYMOUS_CLASS_METHOD container type.
     *
     * @param sequence The statement sequence to analyze
     * @return Set of field names accessed from the outer class
     */
    public static Set<String> findOuterFieldAccess(StatementSequence sequence) {
        if (sequence.containerType() != ContainerType.ANONYMOUS_CLASS_METHOD) {
            return Set.of();
        }

        Set<String> outerFieldAccess = new HashSet<>();
        Node container = sequence.container();

        // Find the anonymous class this method belongs to
        Optional<ObjectCreationExpr> anonymousClassOpt = container.findAncestor(ObjectCreationExpr.class)
                .filter(oce -> oce.getAnonymousClassBody().isPresent());

        if (anonymousClassOpt.isEmpty()) {
            return outerFieldAccess;
        }

        // Find the enclosing (outer) class
        Optional<ClassOrInterfaceDeclaration> outerClassOpt = anonymousClassOpt.get()
                .findAncestor(ClassOrInterfaceDeclaration.class);

        if (outerClassOpt.isEmpty()) {
            return outerFieldAccess;
        }

        ClassOrInterfaceDeclaration outerClass = outerClassOpt.get();

        // Get all field names from the outer class
        Set<String> outerFieldNames = outerClass.getFields().stream()
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toSet());

        // Find all name expressions and field access expressions in the sequence
        for (Statement stmt : sequence.statements()) {
            // Check for direct field references (implicit this.field)
            stmt.findAll(NameExpr.class).forEach(nameExpr -> {
                String name = nameExpr.getNameAsString();
                if (outerFieldNames.contains(name) && !isDeclaredLocally(stmt, name)) {
                    outerFieldAccess.add(name);
                }
            });

            // Check for explicit OuterClass.this.field access
            stmt.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
                if (isOuterClassFieldAccess(fieldAccess, outerClass)) {
                    outerFieldAccess.add(fieldAccess.getNameAsString());
                }
            });
        }

        return outerFieldAccess;
    }

    /**
     * Check if a field access is accessing an outer class field via OuterClass.this.field
     */
    private static boolean isOuterClassFieldAccess(FieldAccessExpr fieldAccess, ClassOrInterfaceDeclaration outerClass) {
        // Check for pattern: OuterClass.this.fieldName
        if (fieldAccess.getScope() instanceof FieldAccessExpr scopeField) {
            if (scopeField.getScope() instanceof NameExpr scopeName
                    && scopeName.getNameAsString().equals(outerClass.getNameAsString())
                    && "this".equals(scopeField.getNameAsString())) {
                return true;
            }
        }

        // Check for pattern: this.fieldName where 'this' refers to outer class
        // This is trickier - we need context to know which 'this' it refers to
        if (fieldAccess.getScope() instanceof ThisExpr thisExpr) {
            // If there's a qualifier (e.g., OuterClass.this), check it
            if (thisExpr.getTypeName().isPresent()) {
                String qualifier = thisExpr.getTypeName().get().asString();
                return qualifier.equals(outerClass.getNameAsString());
            }
        }

        return false;
    }

    /**
     * Check if a variable name is declared locally within the statement (not an outer field).
     */
    private static boolean isDeclaredLocally(Statement stmt, String varName) {
        // Check if the variable is declared in this statement
        return stmt.findAll(VariableDeclarator.class).stream()
                .anyMatch(v -> v.getNameAsString().equals(varName));
    }

    /**
     * Check if the sequence requires access to the outer class instance.
     */
    public static boolean requiresOuterClassAccess(StatementSequence sequence) {
        return !findOuterFieldAccess(sequence).isEmpty();
    }
}
