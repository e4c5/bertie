# Detailed Implementation Plan: Expanding StatementSequence Support

## 1. Goal
Generalize `StatementSequence` to support duplication detection and refactoring in:
- Static Initializers
- Instance Initializers
- Lambdas (Block-bodied)
- Anonymous Classes (within methods/initializers) - **Detection only in Phase 1**
- Inner Classes

**Scope Clarification**: Anonymous class refactoring is deferred to Phase 2 due to complexity around naming and method placement. Phase 1 focuses on detection only.

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
    ANONYMOUS_CLASS_INITIALIZER // For future support of anonymous class refactoring
}
```

### 2.2 StatementSequence Class Update
Convert from `record` to `class` to support complex initialization and helper methods.

```java
public final class StatementSequence {
    private final List<Statement> statements;  // Defensive copy required
    private final Range range;
    private final int startOffset;
    private final Node container;  // Generalized from CallableDeclaration
    private final ContainerType containerType;
    private final CompilationUnit compilationUnit;
    private final Path sourceFilePath;

    public StatementSequence(...) { 
        this.statements = new ArrayList<>(statements);  // Defensive copy
        // ... 
    }

    // Record-style getters for backward compatibility
    public List<Statement> statements() { return statements; }
    public Node container() { return container; }
    public ContainerType containerType() { return containerType; }
    
    // Backward compatibility getter (returns null for non-callables)
    public CallableDeclaration<?> containingCallable() {
        return container instanceof CallableDeclaration<?> c ? c : null;
    }

    // Helper methods to abstract container access
    public Optional<BlockStmt> getCallableBody() {
        // Handles Method, Constructor, Initializer, Lambda
    }
}
```

## 3. Impact Analysis & Investigation

### 3.1 StatementExtractor (Extraction Logic)

**Critical Design Decision - Visitor Recursion Strategy:**
- **DO NOT call `super.visit()` in `InitializerDeclaration` and `LambdaExpr` visitors**
- This prevents redundant extraction and ensures proper boundary detection
- Each container type is extracted in its own visitor pass

**Implementation:**
```java
@Override
public void visit(InitializerDeclaration initializer, Void arg) {
    // IMPORTANT: Do NOT call super.visit() - prevents descending into nested constructs
    ContainerType type = initializer.isStatic() 
        ? ContainerType.STATIC_INITIALIZER 
        : ContainerType.INSTANCE_INITIALIZER;
    extractFromBlock(initializer.getBody(), initializer, type);
}

@Override
public void visit(LambdaExpr lambda, Void arg) {
    // IMPORTANT: Do NOT call super.visit() - prevents redundant extraction
    // Only process block-bodied lambdas
    if (lambda.getBody().isBlockStmt()) {
        extractFromBlock(lambda.getBody().asBlockStmt(), lambda, ContainerType.LAMBDA);
    }
}
```

**Key Points:**
- **Start Offset**: The `startOffset` must be relative to the immediate container's block.
- **Nested Classes**: Statements in a method of an inner class should have that inner class method as the container, not the outer class method. (This is already handled by JavaParser's visitor pattern)
- **processNestedBlocks**: Should stay within the current "statement list" scope. It should NOT cross into nested `BodyDeclaration`s or `LambdaExpr`s - these are handled by their own visitor methods.

### 3.2 Refactoring Engines

#### MethodExtractor Updates
- **Static Context Detection**: When refacting from `STATIC_INITIALIZER`, the extracted method must be `static`. 
- **Lambda Static Context**: For lambdas, determine context by walking up the AST:
  ```java
  boolean isStaticContext(LambdaExpr lambda) {
      return lambda.findAncestor(CallableDeclaration.class)
          .map(CallableDeclaration::isStatic)
          .orElse(false);
  }
  ```
- **Placement**: Use `findAncestor(TypeDeclaration.class)` to locate the target class for method insertion. This already handles inner classes correctly.

#### ParameterResolver - Nested Scope Resolution
**Current Issue**: ParameterResolver assumes single-level callable scope.

**Required Enhancement**: 
```java
// For lambdas, we need to check THREE scope levels:
// 1. Lambda's own parameters (defined in lambda signature)
// 2. Enclosing method's parameters & local variables (effectively final captures)
// 3. Class fields

private Set<VariableInfo> resolveEnclosingScopes(Node container) {
    Set<VariableInfo> enclosingVars = new HashSet<>();
    
    if (container instanceof LambdaExpr lambda) {
        // Add lambda parameters
        lambda.getParameters().forEach(p -> 
            enclosingVars.add(new VariableInfo(p.getNameAsString(), p.getType())));
        
        // Walk up to enclosing callable and add its scope
        lambda.findAncestor(CallableDeclaration.class).ifPresent(callable -> {
            callable.getParameters().forEach(p -> 
                enclosingVars.add(new VariableInfo(p.getNameAsString(), p.getType())));
            // Also scan callable body for local variables
        });
    }
    
    return enclosingVars;
}
```

**Variable Capturing Rules:**
- In a `Method`: variables can be local or fields.
- In a `Lambda`: variables can be:
  1. Local to the lambda
  2. Local to the *enclosing method* (captured, must be effectively final)
  3. Fields of the *enclosing class*
- In a `STATIC_INITIALIZER`: only static fields are accessible

### 3.3 Data Flow Analysis

**Critical Update Required:**
`DataFlowAnalyzer.findVariablesUsedAfter()` currently uses:
```java
Optional<BlockStmt> body = sequence.containingCallable()
    .flatMap(callable -> ...).getBody();
```

**Problem**: For lambdas and initializers, `containingCallable()` returns `null`.

**Fix**: Update to use the new `getCallableBody()` method:
```java
Optional<BlockStmt> body = sequence.getCallableBody();
```

This method now handles all container types (Method, Constructor, Initializer, Lambda).

**Boundary Detection**: 
- For a lambda, "after" means after the sequence but still within the lambda body.
- For an initializer, "after" means after the sequence but still within the initializer block.

### 3.4 Inner and Nested Classes

**Investigation Summary:**
- **Scoping**: Members of inner classes (methods, constructors, initializers) are automatically visited by the `StatementExtractor` visitor pattern. ✅ Already works.
- **Containment**: The JavaParser visitor pattern ensures the `container` in `StatementSequence` is the member definition *within* the inner class.
- **Refactoring Placement**: `MethodExtractor` uses `findContainingType()` which calls `findAncestor(TypeDeclaration.class)` to locate the nearest target for the new helper method. ✅ Should already work for inner classes.

**Verification**: Add test cases to confirm inner class support works as expected.

## 4. Proposed Implementation Steps

### Phase 1: Model & Basic Extraction (COMPLETED ✅)
1. ✅ Create `ContainerType` enum
2. ✅ Convert `StatementSequence` from record to class
3. ✅ Add `Node container` field (generalized from `CallableDeclaration`)
4. ✅ Add backward compatibility methods (`containingCallable()`)
5. ✅ Implement `getCallableBody()` for all container types

### Phase 2: Extraction Layer Updates
1. Add `visit(InitializerDeclaration)` to `StatementExtractor`
   - Do NOT call `super.visit()`
   - Extract from both static and instance initializers
2. Add `visit(LambdaExpr)` to `StatementExtractor`
   - Do NOT call `super.visit()`
   - Only process block-bodied lambdas
3. Update `extractFromBlock()` signature to accept `Node container` and `ContainerType`
4. Update `extractSlidingWindows()` to use `ContainerType` enum instead of `instanceof`
5. Update `getContainerBody()` helper to support all container types
6. Update offset calculation for different container types

### Phase 3: Analysis Layer Updates
1. **DataFlowAnalyzer**:
   - Update `findVariablesUsedAfter()` to use `sequence.getCallableBody()` instead of `sequence.containingCallable()`
   - Verify boundary detection works for initializers and lambdas
2. **ParameterResolver**:
   - Add `resolveEnclosingScopes()` method for nested scope resolution
   - Update captured variable logic to handle lambda parameter capture from enclosing methods
   - Handle static initializer restrictions (only static fields accessible)
3. **RefactoringPriorityComparator**:
   - Verify `isFullBody()` works with updated `getCallableBody()` method
   - **Note**: Should work automatically with no code changes needed ✅

### Phase 4: Refactoring Engine Updates
1. **MethodExtractor**:
   - Add static context detection for lambdas: `isStaticContext(LambdaExpr)`
   - Update `applyMethodModifiers()` to handle `STATIC_INITIALIZER` container type
   - Update `applyMethodModifiers()` to handle `LAMBDA` with static context check
2. **Placement Verification**:
   - Verify `findContainingType()` works correctly for inner classes ✅
   - Test extraction placement in inner classes

### Phase 5: Testing & Verification
1. Create comprehensive test files (see Section 6)
2. Run Golden Cycle verification for each container type
3. Test cross-container duplicates (e.g., method + initializer)
4. Verify no regression in existing functionality

## 5. Risk Mitigation

| Risk | Mitigation | Priority |
|------|------------|----------|
| **Redundant Extractions** | Ensure visitors for nested constructs (lambdas, initializers) do NOT call `super.visit()`. Each container is extracted in its own visitor pass. | HIGH |
| **Incorrect Scoping** | Use `findAncestor(TypeDeclaration.class)` and `findAncestor(CallableDeclaration.class)` to resolve context. For lambdas, implement `resolveEnclosingScopes()` to handle nested variable captures. | HIGH |
| **DataFlowAnalyzer Breakage** | Update to use `sequence.getCallableBody()` instead of `sequence.containingCallable()`. Add null checks for containers without bodies. | HIGH |
| **Static Context Detection** | Implement `isStaticContext()` for lambdas by walking up AST. For initializers, check `InitializerDeclaration.isStatic()`. | MEDIUM |
| **Logic Breakage in Extracts** | Rigorous verification using the **Golden Cycle** with new test-bed cases for each new container type. | HIGH |
| **Anonymous Class Complexity** | Phase 1: Detection only. Defer refactoring to Phase 2 to reduce scope. | LOW |

## 6. Enhanced Verification Plan

### Test Matrix (8 Test Files)
Create comprehensive test files in `/test-bed/src/main/java/com/raditha/bertie/testbed/containers/`:

| Test File | Scenario | Container Types |
|-----------|----------|-----------------|
| `StaticInitializerDups.java` | Duplicate code in static {} blocks | STATIC_INITIALIZER |
| `InstanceInitializerDups.java` | Duplicate code in instance {} blocks | INSTANCE_INITIALIZER |
| `LambdaBlockDups.java` | Block-bodied lambdas with duplicates within lambda | LAMBDA |
| `NestedLambdaDups.java` | Lambdas inside methods, cross-scope duplicates | METHOD + LAMBDA |
| `InnerClassMethodDups.java` | Duplicates in inner class methods | METHOD (in inner class) |
| `StaticInnerClassDups.java` | Duplicates in static inner classes | METHOD (in static inner class) |
| `AnonymousClassDups.java` | Duplicates in anonymous class methods (detection only) | METHOD (in anonymous class) |
| `MixedContainerDups.java` | Cross-container duplicates | METHOD + STATIC_INITIALIZER + INSTANCE_INITIALIZER |

### Golden Cycle Verification
For **each** test file:
1. **Reset**: `./test-bed/reset.sh`
2. **Compile**: `mvn clean compile` in bertie
3. **Run**: `./run-bertie.sh refactor --mode batch --config-file src/main/resources/bertie.yml --verify fast_compile`
4. **Verify**: 
   - `git diff` in test-bed
   - `mvn test-compile` in test-bed (Verification Step 1)
5. **Test**:
   - `git checkout src/test/java` (Reset test files)
   - `mvn test` in test-bed (Verification Step 2)
   - **Rule**: If any tests fail, REVERT and RETRY

### Success Criteria
- ✅ All 8 test files pass Golden Cycle verification
- ✅ No regression in existing test-bed files
- ✅ Refactored code compiles without errors
- ✅ Refactored code passes all tests
- ✅ Extracted methods have correct modifiers (static vs instance)
- ✅ Variable capture works correctly for lambdas
