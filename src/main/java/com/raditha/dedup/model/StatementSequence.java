package com.raditha.dedup.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Represents a sequence of statements that may be a duplicate.
 * Uses JavaParser classes directly - no custom wrappers needed!
 * Leverages Antikythera's AbstractCompiler for CompilationUnit parsing.
 * 
 * @param statements       The actual statement nodes from JavaParser AST
 * @param range            Source code range
 * @param startOffset      Statement index within the containing method (0-based)
 * @param containingCallable The method or constructor containing these statements
 * @param compilationUnit  The parsed file (from AbstractCompiler)
 * @param sourceFilePath   Path to the source file
 */
public record StatementSequence(
        List<Statement> statements,
        Range range,
        int startOffset,
        CallableDeclaration<?> containingCallable,
        CompilationUnit compilationUnit,
        Path sourceFilePath) {

    /**
     * Get method/constructor name.
     */
    public String getMethodName() {
        return containingCallable != null ? containingCallable.getNameAsString() : "unknown";
    }

    /**
     * Helper to get the body of the containing callable.
     */
    public Optional<BlockStmt> getCallableBody() {
        if (containingCallable == null) {
            return Optional.empty();
        }
        if (containingCallable instanceof MethodDeclaration m) {
            return m.getBody();
        } else if (containingCallable instanceof ConstructorDeclaration c) {
            return Optional.of(c.getBody());
        }
        return Optional.empty();
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
                java.util.Objects.equals(sourceFilePath, that.sourceFilePath);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(range, startOffset, sourceFilePath);
    }
}
