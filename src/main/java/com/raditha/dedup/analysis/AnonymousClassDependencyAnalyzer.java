package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.HashSet;
import java.util.Set;

/**
 * Analyzes anonymous class methods for dependencies on the enclosing class.
 */
public final class AnonymousClassDependencyAnalyzer {

    private AnonymousClassDependencyAnalyzer() {}

    public record DependencyResult(Set<String> usedFields,
                                   Set<String> usedMethods) {
        public boolean usesOuterFields() {
            return !usedFields.isEmpty();
        }

        public boolean usesOuterMethods() {
            return !usedMethods.isEmpty();
        }
    }

    public static DependencyResult analyze(MethodDeclaration anonymousMethod,
                                           ClassOrInterfaceDeclaration outerClass) {
        Set<String> outerFields = collectOuterFieldNames(outerClass);
        Set<String> outerMethods = collectOuterMethodNames(outerClass);
        Set<String> localsAndParams = collectLocalAndParameterNames(anonymousMethod);

        Set<String> usedFields = new HashSet<>();
        Set<String> usedMethods = new HashSet<>();

        // Field usage via unqualified name
        anonymousMethod.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();
            if (outerFields.contains(name) && !localsAndParams.contains(name)) {
                usedFields.add(name);
            }
        });

        // Field usage via qualified access (OuterClass.field)
        anonymousMethod.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
            if (fieldAccess.getScope().isNameExpr()
                    && fieldAccess.getScope().asNameExpr().getNameAsString().equals(outerClass.getNameAsString())) {
                usedFields.add(fieldAccess.getNameAsString());
            }
        });

        // Method usage (unqualified call that resolves to outer class method)
        anonymousMethod.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getScope().isEmpty()
                    || call.getScope().get() instanceof ThisExpr
                    || call.getScope().get() instanceof SuperExpr) {
                String name = call.getNameAsString();
                if (outerMethods.contains(name)) {
                    usedMethods.add(name);
                }
            }
        });

        return new DependencyResult(usedFields, usedMethods);
    }

    private static Set<String> collectOuterFieldNames(ClassOrInterfaceDeclaration outerClass) {
        Set<String> names = new HashSet<>();
        outerClass.getFields().forEach(field ->
                field.getVariables().forEach(v -> names.add(v.getNameAsString()))
        );
        return names;
    }

    private static Set<String> collectOuterMethodNames(ClassOrInterfaceDeclaration outerClass) {
        Set<String> names = new HashSet<>();
        outerClass.getMethods().forEach(m -> names.add(m.getNameAsString()));
        return names;
    }

    private static Set<String> collectLocalAndParameterNames(CallableDeclaration<?> method) {
        Set<String> localsAndParams = new HashSet<>();
        method.getParameters().forEach(p -> localsAndParams.add(p.getNameAsString()));
        method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(v -> localsAndParams.add(v.getNameAsString()));
        return localsAndParams;
    }
}
