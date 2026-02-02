package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.model.DuplicateCluster;

import java.nio.file.Path;

/**
 * Utility class for working with test classes during refactoring.
 */
public class TestClassHelper {

    /**
     * Result containing test class information.
     */
    public record TestClassInfo(
            CompilationUnit compilationUnit,
            Path sourceFile,
            ClassOrInterfaceDeclaration testClass) {
    }

    /**
     * Get and validate the test class from a duplicate cluster.
     *
     * @param cluster The duplicate cluster
     * @return Test class information
     * @throws IllegalStateException if no class is found in the compilation unit
     * @throws IllegalArgumentException if the class is not a test class
     */
    public static TestClassInfo getAndValidateTestClass(DuplicateCluster cluster) {
        CompilationUnit cu = cluster.primary().compilationUnit();
        Path sourceFile = cluster.primary().sourceFilePath();

        // Get the test class
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No class found"));

        // Validate this is a test class
        if (!isTestClass(testClass)) {
            throw new IllegalArgumentException("Not a test class: " + testClass.getNameAsString());
        }

        return new TestClassInfo(cu, sourceFile, testClass);
    }

    /**
     * Check if a class is a test class (has @Test methods or test-related annotations).
     */
    public static boolean isTestClass(ClassOrInterfaceDeclaration clazz) {
        // Check if any method has @Test annotation
        return clazz.getMethods().stream()
                .anyMatch(method -> method.getAnnotationByName("Test").isPresent()
                        || method.getAnnotationByName("ParameterizedTest").isPresent()
                        || method.getAnnotationByName("RepeatedTest").isPresent());
    }
}
