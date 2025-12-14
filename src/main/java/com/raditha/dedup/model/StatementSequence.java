package com.raditha.dedup.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a sequence of statements that may be a duplicate.
 * Uses JavaParser classes directly - no custom wrappers needed!
 * Leverages Antikythera's AbstractCompiler for CompilationUnit parsing.
 * 
 * @param statements       The actual statement nodes from JavaParser AST
 * @param range            Source code range
 * @param startOffset      Statement offset within the containing method
 * @param containingMethod The method containing these statements
 * @param compilationUnit  The parsed file (from AbstractCompiler)
 * @param sourceFilePath   Path to the source file
 */
public record StatementSequence(
        List<Statement> statements,
        Range range,
        int startOffset,
        MethodDeclaration containingMethod,
        CompilationUnit compilationUnit,
        Path sourceFilePath) {
    /**
     * Get the class containing this sequence.
     */
    public ClassOrInterfaceDeclaration getContainingClass() {
        if (containingMethod == null)
            return null;
        return containingMethod.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    }

    /**
     * Get all imports from the file.
     */
    public List<String> getImports() {
        if (compilationUnit == null)
            return List.of();
        return compilationUnit.getImports().stream()
                .map(i -> i.getNameAsString())
                .collect(Collectors.toList());
    }

    /**
     * Get method name.
     */
    public String getMethodName() {
        return containingMethod != null ? containingMethod.getNameAsString() : "unknown";
    }

    /**
     * Get class name.
     */
    public String getClassName() {
        ClassOrInterfaceDeclaration clazz = getContainingClass();
        return clazz != null ? clazz.getNameAsString() : "unknown";
    }

    /**
     * Check if this sequence is in a test class.
     * Heuristics: class name ends with "Test" or has @TestInstance annotation.
     */
    public boolean isInTestClass() {
        ClassOrInterfaceDeclaration clazz = getContainingClass();
        if (clazz == null)
            return false;

        return clazz.getNameAsString().endsWith("Test") ||
                clazz.getNameAsString().endsWith("Tests") ||
                clazz.isAnnotationPresent("TestInstance") ||
                clazz.isAnnotationPresent("SpringBootTest");
    }

    /**
     * Check if this is test method (has @Test annotation).
     */
    public boolean isTestMethod() {
        return containingMethod != null &&
                (containingMethod.isAnnotationPresent("Test") ||
                        containingMethod.isAnnotationPresent("ParameterizedTest") ||
                        containingMethod.getNameAsString().startsWith("test"));
    }

    /**
     * Get number of statements in this sequence.
     */
    public int size() {
        return statements != null ? statements.size() : 0;
    }

    /**
     * Get source file name.
     */
    public String getFileName() {
        return sourceFilePath != null ? sourceFilePath.getFileName().toString() : "unknown";
    }

    /**
     * Create a display identifier for this sequence.
     * Format: "FileName.java:methodName [L45-52]"
     */
    public String toDisplayString() {
        return String.format("%s:%s %s",
                getFileName(),
                getMethodName(),
                range.toDisplayString());
    }
}
