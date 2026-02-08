# Detailed Implementation Plan: Expanding StatementSequence Support

## 1. Goal
Generalize `StatementSequence` to support duplication detection and refactoring in:
- Static Initializers
- Instance Initializers
- Lambdas (Block-bodied)
- Anonymous Classes (within methods/initializers)
- Inner Classes

## 2. Core Abstractions

### 2.1 Container Enumeration
We will introduce a `ContainerType` enum to explicitly track the context.

```java
public enum ContainerType {
    METHOD,
    CONSTRUCTOR,
    STATIC_INITIALIZER,
    INSTANCE_INITIALIZER,
    LAMBDA,
    ANONYMOUS_CLASS_INITIALIZER // If we support fields in anonymous classes
}
```

### 2.2 StatementSequence Class Update
```java
public final class StatementSequence {
    private final List<Statement> statements;
    private final Range range;
    private final int startOffset;
    private final Node container;
    private final ContainerType containerType;
    private final CompilationUnit compilationUnit;
    private final Path sourceFilePath;

    public StatementSequence(...) { ... }

    // Getters
    public List<Statement> statements() { return statements; }
    // ... other getters matching record style for compatibility

    // Helper methods to abstract container access (e.g. getContainerBody)
}
```

## 3. Impact Analysis & Investigation

### 3.1 StatementExtractor (Extraction Logic)
**Investigation Required:**
- **Recursion Risk**: If a `LambdaExpr` is inside a `MethodDeclaration`, we must ensure the `MethodVisitor` does not "descend" into the lambda body when extracting method-level sequences, but we *do* want a separate pass (or visitor) to extract sequences *within* that lambda.
- **Start Offset**: The `startOffset` must be relative to the immediate container's block.
- **Nested Classes**: Statements in a method of an inner class should have that inner class method as the container, not the outer class method.

**Design Decision**:
- Specialized `visit` methods for `InitializerDeclaration` and `LambdaExpr`.
- `processNestedBlocks` should stay within the current "statement list" scope. It should NOT cross into nested `BodyDeclaration`s or `LambdaExpr`s. These are handled by the visitor's recursive traversal (via `super.visit`).

### 3.2 Refactoring Engines
- **MethodExtractor**: When refactoring a duplicate in a `STATIC_INITIALIZER`, the extracted method must be `static`. In a `LAMBDA`, we need to check if the lambda is in a static or instance context.
- **ParameterResolver**: Capturing variables:
    - In a `Method`, variables can be local or fields.
    - In a `Lambda`, variables can be local to the lambda, local to the *enclosing method*, or fields of the *enclosing class*.
    - The logic must be updated to correctly identify these "nested" scopes.

### 3.3 Data Flow Analysis
- `DataFlowAnalyzer.findVariablesUsedAfter`: Needs to know the boundary of the container. For a lambda, "after" means after the sequence but still within the lambda body.

### 3.4 Inner and Nested Classes
**Investigation Summary**:
- **Scoping**: Members of inner classes (methods, constructors, initializers) are automatically visited by the `StatementExtractor`.
- **Containment**: We must ensure the `container` in `StatementSequence` is the member definition *within* the inner class, rather than the outer class member that might be currently being visited during traversal.
- **Refactoring Placement**: When a duplicate is found in an inner class, the extracted method should be created inside that inner class. The `RefactoringEngine` should use `findAncestor(TypeDeclaration.class)` to locate the nearest target for the new helper method.

## 4. Proposed Implementation Steps

### Phase 1: Model & Basic Extraction
1. Modify `StatementSequence` class.
2. Update `StatementExtractor` with `InitializerDeclaration` support.
3. Update `StatementExtractor` with `LambdaExpr` support.

### Phase 2: Analysis Updates
1. Adapt `DataFlowAnalyzer` to handle non-callable containers.
2. Adapt `ParameterResolver` for nested scope resolution.
3. Update `RefactoringPriorityComparator` to support new container types in `isFullBody`.

### Phase 3: Refactoring Support
1. Update `MethodExtractor` to handle extraction from initializers and lambdas.
2. Update `ConstructorExtractr` (if applicable, though usually only for constructors).

## 5. Risk Mitigation

| Risk | Mitigation |
|------|------------|
| **Redundant Extractions** | Ensure visitors for nested constructs (lambdas) stop at their own boundaries and don't re-extract from parents. |
| **Incorrect Scoping** | Use `findAncestor(TypeDeclaration.class)` and `findAncestor(CallableDeclaration.class)` to resolve context instead of assuming `containingCallable` is always the top-level. |
| **Logic Breakage in Extracts** | Rigorous verification using the **Golden Cycle** with new test-bed cases for each new container type. |

## 6. Verification Plan
- **Golden Cycle** verification on a dedicated test class.
- New Test Cases:
    - `StaticInitializerDups.java`
    - `LambdaInstanceDups.java`
    - `AnonymousClassDups.java`
