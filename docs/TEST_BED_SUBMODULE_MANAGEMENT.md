# Test-Bed Submodule Management Guide

## Overview

**Important**: The `test-bed` directory is a **separate git repository** (git submodule), not part of the main bertie repository. Changes to test files require separate commits to the test-bed repository.

## Repository Structure

```
bertie/                          # Main repository (github.com/e4c5/bertie)
├── src/                         # Bertie source code
├── docs/                        # Documentation
└── test-bed/                    # Git submodule (github.com/e4c5/bertie-test-bed)
    └── src/
        └── main/java/           # Test files for duplicate detection
```

### Submodule Configuration

From `.gitmodules`:
```
[submodule "test-bed"]
    path = test-bed
    url = git@github.com:e4c5/bertie-test-bed.git
    branch = main
```

## Why Test Files Were Not in the Previous PR

In the previous session, I created 8 test files in `test-bed/src/main/java/com/raditha/bertie/testbed/containers/`:

1. StaticInitializerDups.java
2. InstanceInitializerDups.java
3. LambdaBlockDups.java
4. NestedLambdaDups.java
5. InnerClassMethodDups.java
6. StaticInnerClassDups.java
7. AnonymousClassDups.java
8. MixedContainerDups.java

**However**, these files were only created in the local filesystem. They were **never committed** to the test-bed repository because:

1. The test-bed is a separate git repository with its own commit history
2. I don't have direct push access to the `e4c5/bertie-test-bed` repository
3. Changes to the test-bed require a separate PR to the bertie-test-bed repository

## How to Manage Test-Bed Changes

### Option 1: Manual Workflow (Repository Owner)

If you have access to both repositories:

```bash
# 1. Navigate to the test-bed submodule
cd test-bed

# 2. Create a new branch for your changes
git checkout -b feature/add-container-tests

# 3. Make your changes (create/edit test files)
# ... create test files ...

# 4. Stage and commit changes to test-bed
git add src/main/java/com/raditha/bertie/testbed/containers/
git commit -m "Add 8 test files for container duplicate detection"

# 5. Push to test-bed repository
git push origin feature/add-container-tests

# 6. Create PR in bertie-test-bed repository
# Go to https://github.com/e4c5/bertie-test-bed and create PR

# 7. After test-bed PR is merged, update main bertie repo
cd ..  # Back to main bertie repo
git add test-bed
git commit -m "Update test-bed submodule to include new container tests"
git push
```

### Option 2: Fork and PR Workflow (Contributors)

For contributors without direct access:

```bash
# 1. Fork the bertie-test-bed repository on GitHub

# 2. Update the submodule to point to your fork
cd test-bed
git remote add myfork git@github.com:YOUR_USERNAME/bertie-test-bed.git

# 3. Create a branch and make changes
git checkout -b feature/add-container-tests
# ... create test files ...
git add .
git commit -m "Add container duplicate detection tests"

# 4. Push to your fork
git push myfork feature/add-container-tests

# 5. Create PR from your fork to e4c5/bertie-test-bed

# 6. After merged, contributor creates separate PR to update main bertie repo
```

### Option 3: Recreate Test Files Locally

Since the test files from the previous session were not persisted, you can recreate them using the specifications in `docs/TEST_FILES_GUIDE.md`:

```bash
# Recreate the test files based on the guide
cd test-bed

# Follow the detailed examples in TEST_FILES_GUIDE.md
# Each file has code examples and expected patterns documented
```

## Current State of Test Files

### What Exists in Main Bertie Repo
✅ `docs/expanded_statement_sequence_plan.md` - Updated implementation plan
✅ `docs/TEST_FILES_GUIDE.md` - Comprehensive test file specifications
✅ `docs/TASK_COMPLETION_SUMMARY.md` - Task summary
✅ `src/main/java/com/raditha/dedup/model/ContainerType.java` - Enum for container types
✅ `src/main/java/com/raditha/dedup/model/StatementSequence.java` - Updated model

### What Needs to be Created in Test-Bed Repo
❌ `containers/StaticInitializerDups.java` - Not yet committed
❌ `containers/InstanceInitializerDups.java` - Not yet committed
❌ `containers/LambdaBlockDups.java` - Not yet committed
❌ `containers/NestedLambdaDups.java` - Not yet committed
❌ `containers/InnerClassMethodDups.java` - Not yet committed
❌ `containers/StaticInnerClassDups.java` - Not yet committed
❌ `containers/AnonymousClassDups.java` - Not yet committed
❌ `containers/MixedContainerDups.java` - Not yet committed

❌ Updated model files (User.java, Logger.java, Database.java, Repository.java) - Not yet committed

## Recommended Next Steps

### For Repository Owner (e4c5)

1. **Review the test file specifications** in `docs/TEST_FILES_GUIDE.md`
2. **Decide on the approach**:
   - **Option A**: Use the detailed specifications to recreate the test files yourself
   - **Option B**: Grant me temporary access to create a PR to bertie-test-bed
   - **Option C**: Create the test files incrementally as each implementation phase requires them

3. **Create test files** in test-bed repository following the guide
4. **Update model classes** (User, Logger, Database, Repository) with the methods documented in TEST_FILES_GUIDE.md
5. **Commit to test-bed** repository
6. **Update main bertie repo** to reference the new test-bed commit

### For Contributors

If you're a contributor and want to add these test files:

1. **Fork** the bertie-test-bed repository
2. **Use TEST_FILES_GUIDE.md** as your specification
3. **Create the 8 test files** and update model classes
4. **Create PR** to e4c5/bertie-test-bed
5. After merge, **create separate PR** to main bertie repo updating the submodule reference

## Test File Specifications

All test files are fully documented in `docs/TEST_FILES_GUIDE.md` with:
- Complete file descriptions
- Code examples
- Expected duplicate patterns
- Required model class updates

The guide provides everything needed to recreate the test files exactly as designed.

## Why This Separation Matters

**Benefits of separate test-bed repository:**
1. **Independent versioning** - Test cases can evolve independently
2. **Reusable test suite** - Multiple tools can use the same test cases
3. **Clear separation** - Production code vs test code in different repos
4. **Parallel development** - Tests can be updated without changing main code

**Trade-off:**
- Requires **two PRs** for changes affecting both repos
- More complex workflow for contributors

## Troubleshooting

### "Test files are missing"
→ Check if you're on the correct test-bed commit: `git submodule status`

### "My test changes disappeared"
→ Test-bed is a submodule - changes require commits to the test-bed repository

### "How do I run the tests?"
→ See the Golden Cycle workflow in `docs/expanded_statement_sequence_plan.md`

### "Submodule is detached HEAD"
→ This is normal. Create a branch: `cd test-bed && git checkout -b your-branch`

## References

- Main Repository: https://github.com/e4c5/bertie
- Test-Bed Repository: https://github.com/e4c5/bertie-test-bed
- Test File Guide: `docs/TEST_FILES_GUIDE.md`
- Implementation Plan: `docs/expanded_statement_sequence_plan.md`
