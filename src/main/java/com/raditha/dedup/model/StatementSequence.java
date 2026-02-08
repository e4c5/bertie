package com.raditha.dedup.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
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
 * @param startOffset      Statement index within the containing block (0-based)
 * @param container        The node containing these statements (method, constructor, lambda, initializer, etc.)
 * @param containerType    The type of container (METHOD, CONSTRUCTOR, LAMBDA, etc.)
 * @param compilationUnit  The parsed file (from AbstractCompiler)
 * @param sourceFilePath   Path to the source file
 */
public record StatementSequence(
        List<Statement> statements,
        Range range,
        int startOffset,
        Node container,
        ContainerType containerType,
        CompilationUnit compilationUnit,
        Path sourceFilePath) {

    /**
     * Get the name of the container for display/logging purposes.
     * @return A descriptive name for the container
     */
    public String getContainerName() {
        if (containerType == null) {
            return "unknown";
        }
        return switch (containerType) {
            case METHOD -> container instanceof MethodDeclaration m ? m.getNameAsString() : "method";
            case CONSTRUCTOR -> container instanceof ConstructorDeclaration c ? c.getNameAsString() : "constructor";
            case STATIC_INITIALIZER -> "<static-init>";
            case INSTANCE_INITIALIZER -> "<instance-init>";
            case LAMBDA -> "<lambda@" + range.startLine() + ">";
            case ANONYMOUS_CLASS_METHOD -> {
                if (container instanceof MethodDeclaration m) {
                    yield m.getNameAsString() + "@anonymous";
                }
                yield "anonymous-method";
            }
        };
    }

    /**
     * Get method/constructor name (for backward compatibility).
     * @deprecated Use {@link #getContainerName()} instead for full container type support.
     */
    @Deprecated
    public String getMethodName() {
        if (container instanceof CallableDeclaration<?> callable) {
            return callable.getNameAsString();
        }
        return getContainerName();
    }

    /**
     * Helper to get the body of the containing callable or initializer.
     * @return Optional containing the block statement body, or empty if not applicable
     */
    public Optional<BlockStmt> getCallableBody() {
        if (containerType == null) {
            return Optional.empty();
        }
        return switch (containerType) {
            case METHOD -> container instanceof MethodDeclaration m ? m.getBody() : Optional.empty();
            case CONSTRUCTOR -> container instanceof ConstructorDeclaration c ? Optional.of(c.getBody()) : Optional.empty();
            case STATIC_INITIALIZER, INSTANCE_INITIALIZER -> 
                container instanceof InitializerDeclaration init ? Optional.of(init.getBody()) : Optional.empty();
            case LAMBDA -> {
                if (container instanceof LambdaExpr lambda && lambda.getBody().isBlockStmt()) {
                    yield Optional.of(lambda.getBody().asBlockStmt());
                }
                yield Optional.empty();
            }
            case ANONYMOUS_CLASS_METHOD -> container instanceof MethodDeclaration m ? m.getBody() : Optional.empty();
        };
    }

    /**
     * Check if this sequence is in a static context.
     * @return true if the container is definitely in a static context
     */
    public boolean isStaticContext() {
        if (containerType == null) {
            return false;
        }
        return switch (containerType) {
            case STATIC_INITIALIZER -> true;
            case METHOD -> container instanceof MethodDeclaration m && m.isStatic();
            case CONSTRUCTOR, INSTANCE_INITIALIZER -> false;
            case LAMBDA, ANONYMOUS_CLASS_METHOD -> determineEnclosingStaticContext();
        };
    }

    /**
     * Determine if the enclosing context is static for lambdas and anonymous classes.
     */
    private boolean determineEnclosingStaticContext() {
        // Walk up the AST to find the enclosing method/initializer
        Node current = container;
        while (current != null) {
            if (current instanceof MethodDeclaration m) {
                return m.isStatic();
            } else if (current instanceof InitializerDeclaration init) {
                return init.isStatic();
            } else if (current instanceof ConstructorDeclaration) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * Get the container as a CallableDeclaration if applicable.
     * @return Optional containing the callable, or empty for non-callable containers
     */
    public Optional<CallableDeclaration<?>> getContainingCallable() {
        if (containerType != null && containerType.isCallable() && container instanceof CallableDeclaration<?> callable) {
            return Optional.of(callable);
        }
        return Optional.empty();
    }

    /**
     * Get number of statements in this sequence.
     */
    public int size() {
        return statements != null ? statements.size() : 0;
    }

    /**
     * Checks equality based on location (file, range, offset).
     *
     * @param o Object to compare
     * @return true if locations match
     */
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

    /**
     * Generates hash code based on location.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(range, startOffset, sourceFilePath);
    }
}
