package com.raditha.dedup.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;

import java.nio.file.Path;
import java.util.List;

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
     * Get method name.
     */
    public String getMethodName() {
        return containingMethod != null ? containingMethod.getNameAsString() : "unknown";
    }

    /**
     * Get number of statements in this sequence.
     */
    public int size() {
        return statements != null ? statements.size() : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        StatementSequence that = (StatementSequence) o;
        return startOffset == that.startOffset &&
                java.util.Objects.equals(range, that.range) &&
                (sourceFilePath == null ? that.sourceFilePath == null
                        : (that.sourceFilePath != null && sourceFilePath.toAbsolutePath().normalize().toString()
                                .equals(that.sourceFilePath.toAbsolutePath().normalize().toString())));
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(range, startOffset,
                sourceFilePath != null ? sourceFilePath.toAbsolutePath().normalize().toString() : 0);
    }
}
