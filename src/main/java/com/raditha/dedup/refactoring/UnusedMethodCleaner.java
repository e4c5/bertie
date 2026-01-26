package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithPrivateModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and removes private methods that are no longer used after refactoring.
 * This acts as a garbage collector for helper methods that were orphaned.
 */
public class UnusedMethodCleaner {

    private static final Logger logger = LoggerFactory.getLogger(UnusedMethodCleaner.class);

    private static final Set<String> PRESERVED_ANNOTATIONS = Set.of(
        "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "ParameterizedTest", "Bean"
    );

    /**
     * Removes unused private methods from the compilation unit.
     * @return true if any method was removed.
     */
    public boolean clean(CompilationUnit cu) {
        boolean modified = false;
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cleanClass(clazz)) {
                modified = true;
            }
        }
        return modified;
    }

    private boolean cleanClass(ClassOrInterfaceDeclaration clazz) {
        List<MethodDeclaration> privateMethods = clazz.getMethods().stream()
            .filter(NodeWithPrivateModifier::isPrivate)
            .toList();

        if (privateMethods.isEmpty()) {
            return false;
        }

        Set<String> usedMethodNames = collectUsedMethodNames(clazz);
        boolean modified = false;

        for (MethodDeclaration method : privateMethods) {
            String name = method.getNameAsString();

            // 1. Check if method is used (called or referenced)
            // 2. Check for annotations that implicitly use the method
            if (usedMethodNames.contains(name)  && hasPreservedAnnotation(method)) {
                continue;
            }

            // 3. Remove method
            logger.info("Removing unused private method: {} in class {}", name, clazz.getNameAsString());
            method.remove();
            modified = true;
        }

        return modified;
    }

    private Set<String> collectUsedMethodNames(ClassOrInterfaceDeclaration clazz) {
        Set<String> used = new HashSet<>();

        // Direct calls
        clazz.findAll(MethodCallExpr.class).forEach(call -> 
            used.add(call.getNameAsString())
        );

        // Method references (this::methodName)
        clazz.findAll(MethodReferenceExpr.class).forEach(ref -> 
            used.add(ref.getIdentifier())
        );

        // @MethodSource usages (value="methodName")
        clazz.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
            .filter(a -> a.getNameAsString().endsWith("MethodSource"))
            .forEach(a -> {
                if (a.isSingleMemberAnnotationExpr()) {
                    // @MethodSource("name")
                    a.asSingleMemberAnnotationExpr().getMemberValue().ifStringLiteralExpr(s -> 
                        used.add(s.getValue())
                    );
                } else if (a.isNormalAnnotationExpr()) {
                    a.asNormalAnnotationExpr().getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("value"))
                        .forEach(p -> {
                            p.getValue().ifStringLiteralExpr(s -> used.add(s.getValue()));
                            p.getValue().ifArrayInitializerExpr(arr -> 
                                arr.getValues().forEach(v -> 
                                    v.ifStringLiteralExpr(s -> used.add(s.getValue()))
                                )
                            );
                        });
                }
            });

        return used;
    }

    private boolean hasPreservedAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> PRESERVED_ANNOTATIONS.contains(a.getNameAsString()));
    }
}
