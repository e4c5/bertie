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
 * Now supports various container types beyond just methods/constructors:
 * - Methods and Constructors
 * - Static and Instance Initializers
 * - Lambda Expressions
 * - Anonymous Classes
 */
public final class StatementSequence {
    private final List<Statement> statements;
    private final Range range;
    private final int startOffset;
    private final Node container;
    private final ContainerType containerType;
    private final CompilationUnit compilationUnit;
    private final Path sourceFilePath;

    /**
     * Constructor for backward compatibility with CallableDeclaration containers.
     */
    public StatementSequence(
            List<Statement> statements,
            Range range,
            int startOffset,
            CallableDeclaration<?> containingCallable,
            CompilationUnit compilationUnit,
            Path sourceFilePath) {
        this(statements, range, startOffset, containingCallable, 
             inferContainerType(containingCallable), compilationUnit, sourceFilePath);
    }

    /**
     * Full constructor with container type support.
     */
    public StatementSequence(
            List<Statement> statements,
            Range range,
            int startOffset,
            Node container,
            ContainerType containerType,
            CompilationUnit compilationUnit,
            Path sourceFilePath) {
        this.statements = statements;
        this.range = range;
        this.startOffset = startOffset;
        this.container = container;
        this.containerType = containerType;
        this.compilationUnit = compilationUnit;
        this.sourceFilePath = sourceFilePath;
    }

    /**
     * Infer container type from a CallableDeclaration for backward compatibility.
     */
    private static ContainerType inferContainerType(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration) {
            return ContainerType.METHOD;
        } else if (callable instanceof ConstructorDeclaration) {
            return ContainerType.CONSTRUCTOR;
        }
        return ContainerType.METHOD; // Default fallback
    }

    // Record-style getters for backward compatibility
    public List<Statement> statements() { return statements; }
    public Range range() { return range; }
    public int startOffset() { return startOffset; }
    public Node container() { return container; }
    public ContainerType containerType() { return containerType; }
    public CompilationUnit compilationUnit() { return compilationUnit; }
    public Path sourceFilePath() { return sourceFilePath; }

    /**
     * Get the containing callable (for backward compatibility).
     * Returns null for non-callable containers like initializers and lambdas.
     */
    public CallableDeclaration<?> containingCallable() {
        if (container instanceof CallableDeclaration<?> callable) {
            return callable;
        }
        return null;
    }

    /**
     * Get method/constructor/container name.
     */
    public String getMethodName() {
        if (container instanceof CallableDeclaration<?> callable) {
            return callable.getNameAsString();
        } else if (container instanceof InitializerDeclaration init) {
            return init.isStatic() ? "<static-init>" : "<init>";
        } else if (container instanceof LambdaExpr) {
            return "<lambda>";
        }
        return "unknown";
    }

    /**
     * Helper to get the body of the container.
     */
    public Optional<BlockStmt> getCallableBody() {
        if (container instanceof MethodDeclaration m) {
            return m.getBody();
        } else if (container instanceof ConstructorDeclaration c) {
            return Optional.of(c.getBody());
        } else if (container instanceof InitializerDeclaration init) {
            return Optional.of(init.getBody());
        } else if (container instanceof LambdaExpr lambda) {
            if (lambda.getBody().isBlockStmt()) {
                return Optional.of(lambda.getBody().asBlockStmt());
            }
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
