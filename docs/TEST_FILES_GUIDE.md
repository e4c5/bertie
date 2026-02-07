# Enhanced Test Files for Container Support

This document describes the 8 enhanced test files created in the test-bed submodule to validate duplicate detection and refactoring in various Java container types.

## Test File Locations

All test files are located in: `/test-bed/src/main/java/com/raditha/bertie/testbed/containers/`

## Test Files Overview

### 1. StaticInitializerDups.java
**Purpose**: Test duplicate code detection in static initializer blocks (`static { }`)

**Key Features**:
- Multiple static initializer blocks with duplicate sequences
- Tests static field access
- Validates that extracted method will be static

**Example Duplicates**:
```java
static {
    logger = new Logger();
    logger.setLevel("INFO");
    logger.setFormat("JSON");
    logger.enable();
    System.out.println("Logger initialized");
}
```

### 2. InstanceInitializerDups.java
**Purpose**: Test duplicate code detection in instance initializer blocks (`{ }`)

**Key Features**:
- Multiple instance initializer blocks with duplicate sequences
- Tests instance field access
- Validates that extracted method will be non-static (instance method)

**Example Duplicates**:
```java
{
    logger = new Logger();
    logger.setLevel("DEBUG");
    logger.setFormat("TEXT");
    logger.enable();
    System.out.println("Instance logger initialized");
}
```

### 3. LambdaBlockDups.java
**Purpose**: Test duplicate code detection within block-bodied lambda expressions

**Key Features**:
- Duplicates within lambda bodies
- Lambda parameter usage
- Different lambda contexts (forEach, Consumer, Stream operations)

**Example Duplicates**:
```java
users.forEach(user -> {
    user.setActive(true);
    user.setLastModified(System.currentTimeMillis());
    user.save();
    System.out.println("User processed: " + user.getId());
});
```

### 4. NestedLambdaDups.java
**Purpose**: Test cross-scope duplicates between methods and lambdas

**Key Features**:
- Duplicates that appear in both method scope and lambda scope
- Variable capture from enclosing method
- Static vs instance context in lambdas
- Tests that extracted method handles captured variables correctly

**Example Duplicates**:
```java
public void processUserList(List<User> users) {
    String prefix = "USER_";
    
    logger.info("Starting processing");  // Duplicate code
    logger.setLevel("DEBUG");
    System.out.println("Process started at: " + System.currentTimeMillis());
    
    users.forEach(user -> {
        String userId = prefix + user.getId();  // Uses captured variable
        user.setExternalId(userId);
        user.save();
    });
}
```

### 5. InnerClassMethodDups.java
**Purpose**: Test duplicate detection in inner (non-static nested) class methods

**Key Features**:
- Multiple inner classes with their own duplicates
- Access to outer class fields
- Validates that extracted methods are placed in the inner class, not outer class

**Example Structure**:
```java
public class InnerClassMethodDups {
    private Logger logger;
    
    public class UserProcessor {
        public void processUser1(User user) {
            // Duplicate code that accesses outer class 'logger'
            user.setActive(true);
            user.save();
            logger.info("User processed in inner class");
        }
        
        public void processUser2(User user) {
            // Same duplicate
        }
    }
}
```

### 6. StaticInnerClassDups.java
**Purpose**: Test duplicate detection in static inner class methods

**Key Features**:
- Static inner classes (cannot access outer instance fields)
- Both static and instance methods in static inner class
- Validates that extracted methods are static when appropriate

**Example Structure**:
```java
public class StaticInnerClassDups {
    private static Logger staticLogger;
    
    public static class StaticProcessor {
        public static void processUser1(User user) {
            // Duplicate code - should extract to static method
            user.setActive(true);
            user.save();
            staticLogger.info("User processed in static inner class");
        }
    }
}
```

### 7. AnonymousClassDups.java
**Purpose**: Test duplicate detection in anonymous class methods (Phase 1: Detection Only)

**Key Features**:
- Anonymous classes implementing interfaces (Comparator, Runnable)
- Anonymous classes extending Object with custom methods
- Phase 1: Detection only (refactoring deferred due to naming/placement complexity)

**Example Structure**:
```java
public Comparator<User> createComparator1() {
    return new Comparator<User>() {
        @Override
        public int compare(User u1, User u2) {
            // Duplicate code in anonymous class method
            if (u1 == null || u2 == null) {
                throw new IllegalArgumentException("Users cannot be null");
            }
            logger.debug("Comparing users");
            return u1.getId().compareTo(u2.getId());
        }
    };
}
```

### 8. MixedContainerDups.java
**Purpose**: Test cross-container duplicates (same code in different container types)

**Key Features**:
- Same duplicate appearing in static initializer, instance initializer, static method, and instance method
- Validates that extracted method is static if ANY occurrence is in static context
- Tests the most complex scenario for duplicate elimination

**Example Structure**:
```java
// Same duplicate in multiple container types:
static {
    logger = new Logger();
    logger.setLevel("INFO");
    logger.enable();  // Duplicate block
}

{
    instanceLogger = new Logger();
    instanceLogger.setLevel("INFO");
    instanceLogger.enable();  // Same duplicate
}

public static void initializeStatic() {
    staticLogger = new Logger();
    staticLogger.setLevel("INFO");
    staticLogger.enable();  // Same duplicate again
}
```

## Model Updates

To support these test files, the following model classes were updated:

### User.java
Added methods:
- `validate()` - Validation logic
- `setLastModified(long)` / `getLastModified()` - Timestamp tracking
- `setExternalId(String)` / `getExternalId()` - External ID management
- `setTag(String)` / `getTag()` - Tag management
- `setStatus(String)` - Status setter (overload)

### Repository.java
Added method:
- `findAll()` - Return all entities

## Testing Strategy

Each test file will be validated using the **Golden Cycle**:

1. **Reset**: `./test-bed/reset.sh`
2. **Compile**: `mvn clean compile` in bertie
3. **Run**: `./run-bertie.sh refactor --mode batch --config-file src/main/resources/bertie.yml --verify fast_compile`
4. **Verify**: Check `git diff` and run `mvn test-compile` in test-bed
5. **Test**: Reset test files and run `mvn test` in test-bed

## Expected Outcomes

### Phase 1 (Current - Model & Basic Extraction)
- ✅ ContainerType enum created
- ✅ StatementSequence converted to class
- ✅ Test files created and ready

### Phase 2 (Extraction Layer)
- [ ] StatementExtractor detects duplicates in all 8 test files
- [ ] Proper boundary detection for each container type
- [ ] No redundant extractions

### Phase 3 (Analysis Layer)
- [ ] DataFlowAnalyzer correctly identifies variable scope boundaries
- [ ] ParameterResolver handles nested scopes (especially for lambdas)
- [ ] RefactoringPriorityComparator ranks duplicates correctly

### Phase 4 (Refactoring Engine)
- [ ] MethodExtractor creates correct method signatures (static vs instance)
- [ ] Extracted methods placed in correct class (especially for inner classes)
- [ ] Variable capture works correctly for lambdas

### Phase 5 (Verification)
- [ ] All 8 test files pass Golden Cycle
- [ ] No test failures after refactoring
- [ ] Code compiles without errors
- [ ] No regression in existing functionality

## Notes

- **Anonymous class refactoring** is explicitly deferred to Phase 2 due to complexity
- Phase 1 will detect duplicates in anonymous classes but not refactor them
- Inner class support likely already works due to JavaParser's visitor pattern
- The most challenging aspects will be:
  1. Lambda variable capture from enclosing scopes
  2. Static context detection for lambdas
  3. Cross-container duplicates requiring static extraction
