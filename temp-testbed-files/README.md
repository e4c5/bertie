# Test-Bed Files for Container Support

This directory contains all test files and model updates for the container support feature.

## Overview

These files test duplicate code detection and refactoring in various Java container types:
- Static initializer blocks
- Instance initializer blocks
- Lambda expressions
- Inner classes
- Static inner classes
- Anonymous classes
- Mixed containers

## Contents

### Container Test Files (8 files)
Located in `containers/`:

1. **StaticInitializerDups.java** (63 lines)
   - Tests duplicate detection in static `{ }` blocks
   - Container type: `STATIC_INITIALIZER`

2. **InstanceInitializerDups.java** (67 lines)
   - Tests duplicate detection in instance `{ }` blocks
   - Container type: `INSTANCE_INITIALIZER`

3. **LambdaBlockDups.java** (81 lines)
   - Tests duplicate detection in block-bodied lambdas
   - Container type: `LAMBDA`

4. **NestedLambdaDups.java** (80 lines)
   - Tests cross-scope duplicates (method + lambda)
   - Tests nested scope resolution

5. **InnerClassMethodDups.java** (93 lines)
   - Tests duplicates in non-static inner class methods
   - Validates inner class handling

6. **StaticInnerClassDups.java** (88 lines)
   - Tests duplicates in static inner class methods
   - Validates static context detection

7. **AnonymousClassDups.java** (106 lines)
   - Tests duplicates in anonymous class methods
   - Detection only (refactoring deferred)

8. **MixedContainerDups.java** (98 lines)
   - Tests cross-container duplicates
   - Most complex test case

### Model Class Updates (4 files)

#### model/User.java
Adds 7 methods to existing User class:
- `validate()` - Throws exception if id or email is null
- `save()` - Simulates saving to database
- `setLastModified(long)` / `getLastModified()` - Timestamp management
- `setExternalId(String)` / `getExternalId()` - External ID management
- `setTag(String)` / `getTag()` - Tag management
- `setStatus(String)` - String version of status setter

#### model/Logger.java
Adds 7 methods to existing Logger class:
- `debug(String)` - Debug level logging
- `setLevel(String)` / `getLevel()` - Level management
- `setFormat(String)` / `getFormat()` - Format management
- `enable()` / `disable()` / `isEnabled()` - Enable/disable logging

#### model/Database.java
Adds 7 methods to existing Database class:
- `connect(String)` / `getConnectionString()` - Connection management
- `setMaxConnections(int)` / `getMaxConnections()` - Connection pool size
- `enablePooling()` / `disablePooling()` / `isPoolingEnabled()` - Pooling management

#### repository/Repository.java
Adds 1 method to existing Repository interface:
- `findAll()` - Returns List<T> of all entities

## Deployment Instructions

### Step 1: Navigate to test-bed repository

```bash
cd test-bed
git checkout -b feature/add-container-tests
```

### Step 2: Copy container test files

```bash
# Create containers directory
mkdir -p src/main/java/com/raditha/bertie/testbed/containers

# Copy all container test files
cp ../temp-testbed-files/containers/*.java src/main/java/com/raditha/bertie/testbed/containers/
```

### Step 3: Update model classes

```bash
# Copy model updates (these will need to be merged with existing files)
cp ../temp-testbed-files/model/User.java src/main/java/com/raditha/bertie/testbed/model/
cp ../temp-testbed-files/model/Logger.java src/main/java/com/raditha/bertie/testbed/model/
cp ../temp-testbed-files/model/Database.java src/main/java/com/raditha/bertie/testbed/model/
cp ../temp-testbed-files/repository/Repository.java src/main/java/com/raditha/bertie/testbed/repository/
```

**Note**: If the model files already exist, you'll need to manually merge the new methods into the existing files.

### Step 4: Compile and verify

```bash
mvn clean compile
```

All files should compile successfully.

### Step 5: Commit and push

```bash
git add -A
git commit -m "Add 8 container test files and update model classes

- Add StaticInitializerDups for static initializer block testing
- Add InstanceInitializerDups for instance initializer block testing
- Add LambdaBlockDups for lambda expression testing
- Add NestedLambdaDups for cross-scope testing
- Add InnerClassMethodDups for inner class testing
- Add StaticInnerClassDups for static inner class testing
- Add AnonymousClassDups for anonymous class testing (detection only)
- Add MixedContainerDups for cross-container testing
- Update User, Logger, Database classes with required methods
- Update Repository interface with findAll() method"

git push -u origin feature/add-container-tests
```

### Step 6: Create Pull Request

Create a PR in the `e4c5/bertie-test-bed` repository with the title:
```
Add container test files for duplicate detection testing
```

Description:
```
This PR adds 8 comprehensive test files to validate duplicate code detection
and refactoring in various Java container types beyond methods and constructors.

Container types tested:
- Static initializer blocks
- Instance initializer blocks
- Lambda expressions (block-bodied)
- Nested lambdas with cross-scope duplicates
- Inner class methods
- Static inner class methods
- Anonymous class methods (detection only)
- Mixed containers (cross-container duplicates)

Also updates model classes (User, Logger, Database) and Repository interface
with methods required by the test files.

Related to: e4c5/bertie PR #XXX (container support implementation)
```

### Step 7: Update main repository submodule

After the test-bed PR is merged:

```bash
cd ..  # Back to main bertie repository
cd test-bed
git checkout main
git pull origin main
cd ..
git add test-bed
git commit -m "Update test-bed submodule to include container tests"
git push origin your-branch-name
```

### Step 8: Clean up temporary files

```bash
git rm -rf temp-testbed-files
git commit -m "Remove temporary test files (deployed to test-bed)"
git push origin your-branch-name
```

## Testing

Once deployed, test with the Golden Cycle:

```bash
# 1. Reset test-bed
cd test-bed
./reset.sh
cd ..

# 2. Compile bertie
mvn clean compile

# 3. Run refactoring
./run-bertie.sh refactor --mode batch --config-file src/main/resources/bertie.yml

# 4. Verify changes in test-bed
cd test-bed
git diff  # Review refactored code

# 5. Verify compilation
mvn test-compile

# 6. Run tests
mvn test
```

## Expected Results

After running Bertie, you should see:
- Duplicate blocks extracted into helper methods
- Static methods created for duplicates in static contexts
- Instance methods created for duplicates in instance contexts
- Proper static context detection for lambdas
- All tests still passing after refactoring

## File Summary

| Category | Files | Lines | Description |
|----------|-------|-------|-------------|
| Container Tests | 8 | 676 | Test files for various container types |
| Model Updates | 4 | 88 | Additional methods for test support |
| **Total** | **12** | **764** | **Complete test infrastructure** |

## Related Documentation

- Main implementation plan: `docs/expanded_statement_sequence_plan.md`
- Test file guide: `docs/TEST_FILES_GUIDE.md`
- Implementation status: `docs/TASK_COMPLETION_SUMMARY.md`

## Questions?

If you have questions or encounter issues, refer to the documentation in the main bertie repository's `docs/` directory.
