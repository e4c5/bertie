# Fixed Test-Bed Files - Compatible with Original Model Classes

## Overview

This directory contains **updated test files** that work with the **original User, Logger, and Database classes** in the test-bed repository, without requiring any modifications to those model classes.

## What Changed

### Previous Version Issues
The original test files assumed methods that didn't exist in the model classes:
- `Logger(String)` constructor
- `Logger.setLevel()`, `setFormat()`, `enable()`
- `User.setStatus()`, `setTag()`, `validate()`, `getTag()`, `setLastModified()`
- `Database.connect()`, `setMaxConnections()`, etc.

### Fixed Version
These new test files:
- ✅ **No model class dependencies** - Use simple data types (String, int, List, Map)
- ✅ **Self-contained duplicates** - All duplicate code is in the test files themselves
- ✅ **Same test coverage** - Still demonstrate all container types
- ✅ **Compile without errors** - Work with original model classes

## Test Files

### 1. StaticInitializerDups.java (55 lines)
- Tests duplicate detection in `static { }` blocks
- Uses List<String> and simple variables
- No external dependencies

### 2. InstanceInitializerDups.java (69 lines)
- Tests duplicate detection in instance `{ }` blocks
- Uses Map<String, Object> and timestamps
- No external dependencies

### 3. LambdaBlockDups.java (66 lines)
- Tests duplicate detection in block-bodied lambdas
- Uses Consumer<String> and Function<String, String>
- No external dependencies

### 4. NestedLambdaDups.java (65 lines)
- Tests cross-scope duplicates (method + lambda)
- Uses Stream API and lambda expressions
- No external dependencies

### 5. InnerClassMethodDups.java (62 lines)
- Tests duplicates in inner class methods
- Uses simple String processing
- No external dependencies

### 6. StaticInnerClassDups.java (63 lines)
- Tests duplicates in static inner classes
- Validates static context detection
- No external dependencies

### 7. AnonymousClassDups.java (83 lines)
- Tests duplicates in anonymous class methods
- Uses interface implementations
- Detection only (Phase 1)

### 8. MixedContainerDups.java (92 lines)
- Tests cross-container duplicates
- All container types in one file
- No external dependencies

## Deployment Instructions

### Quick Copy

```bash
# Navigate to test-bed repository
cd /path/to/bertie/test-bed

# Create containers directory
mkdir -p src/main/java/com/raditha/bertie/testbed/containers

# Copy all test files
cp /path/to/bertie/fixed-testbed-files/containers/*.java \
   src/main/java/com/raditha/bertie/testbed/containers/

# Compile to verify
mvn clean compile
```

### Verify Compilation

```bash
cd test-bed
mvn clean compile

# Should complete without errors
# Expected output: BUILD SUCCESS
```

### Run Bertie

```bash
cd ..
./run-bertie.sh refactor --mode batch --config-file src/main/resources/bertie.yml
```

## Key Differences from Original

| Aspect | Original Files | Fixed Files |
|--------|---------------|-------------|
| Model Dependencies | Required User.setStatus(), Logger.setLevel(), etc. | None - self-contained |
| Compilation | Failed with original model classes | ✅ Compiles successfully |
| Duplicate Patterns | Model method calls | Simple variable operations |
| Test Coverage | 8 container types | ✅ Same 8 container types |
| Lines of Code | ~720 lines | ~600 lines (simpler) |

## What Each File Tests

1. **StaticInitializerDups** → Static initializer block duplicates
2. **InstanceInitializerDups** → Instance initializer block duplicates  
3. **LambdaBlockDups** → Lambda expression duplicates
4. **NestedLambdaDups** → Cross-scope (method + lambda) duplicates
5. **InnerClassMethodDups** → Inner class method duplicates
6. **StaticInnerClassDups** → Static inner class duplicates
7. **AnonymousClassDups** → Anonymous class duplicates (detection only)
8. **MixedContainerDups** → All container types (most complex)

## Expected Bertie Behavior

After running Bertie on these files:

### Detection Phase
- Should detect all duplicate blocks
- Should identify correct container types
- Should cluster similar duplicates

### Refactoring Phase
- Should extract helper methods from duplicates
- Should preserve static/instance context
- Should place extracted methods correctly
- Anonymous class duplicates: Detection only (no refactoring in Phase 1)

### Verification
```bash
cd test-bed
git diff  # Review changes
mvn test-compile  # Should compile
mvn test  # Should pass (if tests exist)
```

## No Model Changes Required

**Important**: These test files do NOT require any changes to:
- ✅ User.java
- ✅ Logger.java
- ✅ Database.java
- ✅ Repository.java

All original model classes can remain unchanged!

## Summary

- ✅ All 8 test files ready
- ✅ No compilation errors
- ✅ No model class modifications needed
- ✅ Same test coverage
- ✅ Ready for deployment
