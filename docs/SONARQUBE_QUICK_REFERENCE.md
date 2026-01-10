# SonarQube Quick Reference - Common Java Issues

## Quick Fixes for Common SonarQube Warnings

### 1. Cognitive Complexity (java:S3776)
**Issue**: Method has cognitive complexity of X (threshold is 15)

**Fix**: Break the method into smaller methods
```java
// Before
public void processAll(List<Item> items) {
    for (Item item : items) {
        if (item.isValid()) {
            if (item.needsProcessing()) {
                // complex logic here
            }
        }
    }
}

// After
public void processAll(List<Item> items) {
    items.stream()
        .filter(Item::isValid)
        .filter(Item::needsProcessing)
        .forEach(this::processItem);
}

private void processItem(Item item) {
    // complex logic here
}
```

### 2. Null Pointer Dereference (java:S2259)
**Issue**: NullPointerException might be thrown

**Fix**: Add null checks or use Optional
```java
// Before
public String getName(User user) {
    return user.getName(); // NPE if user is null
}

// After - Option 1: Null check
public String getName(User user) {
    return user != null ? user.getName() : "Unknown";
}

// After - Option 2: Optional
public Optional<String> getName(User user) {
    return Optional.ofNullable(user)
                   .map(User::getName);
}
```

### 3. Resources Should Be Closed (java:S2095)
**Issue**: Resource leak - file/stream not closed

**Fix**: Use try-with-resources
```java
// Before
public List<String> readFile(String path) throws IOException {
    BufferedReader reader = new BufferedReader(new FileReader(path));
    return reader.lines().collect(Collectors.toList());
}

// After
public List<String> readFile(String path) throws IOException {
    try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
        return reader.lines().collect(Collectors.toList());
    }
}
```

### 4. Magic Numbers (java:S109)
**Issue**: Magic numbers should be replaced by constants

**Fix**: Define named constants
```java
// Before
if (score > 75) {
    return "Pass";
}

// After
private static final int PASSING_SCORE = 75;

if (score > PASSING_SCORE) {
    return "Pass";
}
```

### 5. Missing JavaDoc (java:S1133, java:S1135)
**Issue**: Public API should be documented

**Fix**: Add JavaDoc comments
```java
// Before
public class DuplicationAnalyzer {
    public List<Duplicate> analyze(List<File> files) {
        // ...
    }
}

// After
/**
 * Analyzes source files for code duplication.
 * 
 * @author Bertie Team
 * @since 1.0.0
 */
public class DuplicationAnalyzer {
    /**
     * Analyzes the provided files for duplicate code.
     *
     * @param files the list of files to analyze
     * @return list of detected duplicates
     * @throws IllegalArgumentException if files list is null or empty
     */
    public List<Duplicate> analyze(List<File> files) {
        // ...
    }
}
```

### 6. Exception Should Not Be Caught Generic (java:S1181)
**Issue**: Don't catch generic Exception

**Fix**: Catch specific exceptions
```java
// Before
try {
    processFile(file);
} catch (Exception e) {
    log.error("Error", e);
}

// After
try {
    processFile(file);
} catch (IOException e) {
    log.error("IO error processing file: {}", file, e);
} catch (ParseException e) {
    log.error("Parse error in file: {}", file, e);
}
```

### 7. String Literals Should Not Be Duplicated (java:S1192)
**Issue**: String literal appears multiple times

**Fix**: Use constants
```java
// Before
log.info("Processing started");
// ... later
log.info("Processing started");

// After
private static final String MSG_PROCESSING_STARTED = "Processing started";

log.info(MSG_PROCESSING_STARTED);
// ... later
log.info(MSG_PROCESSING_STARTED);
```

### 8. Collapsible If Statements (java:S1066)
**Issue**: Nested if statements can be combined

**Fix**: Merge conditions
```java
// Before
if (user != null) {
    if (user.isActive()) {
        process(user);
    }
}

// After
if (user != null && user.isActive()) {
    process(user);
}
```

### 9. Method Parameters Should Be Final (java:S1135)
**Issue**: Parameters should be final or effectively final

**Fix**: Add final modifier
```java
// Before
public void process(String name, int count) {
    // ...
}

// After
public void process(final String name, final int count) {
    // ...
}
```

### 10. Unused Imports (java:S1128)
**Issue**: Unused import statement

**Fix**: Remove the unused import (IDEs can do this automatically)

### 11. Empty Blocks Should Be Removed (java:S108)
**Issue**: Empty catch/if/else block

**Fix**: Either remove the block or add meaningful code
```java
// Before
try {
    riskyOperation();
} catch (IOException e) {
    // TODO: handle exception
}

// After
try {
    riskyOperation();
} catch (IOException e) {
    log.error("Failed to perform risky operation", e);
    throw new ProcessingException("Operation failed", e);
}
```

### 12. Boolean Expressions Should Not Be Gratuitous (java:S1125)
**Issue**: Unnecessary boolean literal in expression

**Fix**: Simplify the expression
```java
// Before
if (condition == true) { ... }
return condition ? true : false;

// After
if (condition) { ... }
return condition;
```

### 13. Stream Operations Should Not Be Nested (java:S3958)
**Issue**: Nested stream operations reduce readability

**Fix**: Flatten the stream operations
```java
// Before
list.stream()
    .map(item -> item.getChildren().stream()
        .filter(child -> child.isValid())
        .collect(Collectors.toList()))
    .flatMap(Collection::stream)
    .collect(Collectors.toList());

// After
list.stream()
    .flatMap(item -> item.getChildren().stream())
    .filter(Child::isValid)
    .collect(Collectors.toList());
```

## Priority Order for Fixes

1. **Blocker** - Fix immediately (security vulnerabilities, critical bugs)
2. **Critical** - Fix before release (major bugs)
3. **Major** - Fix soon (code smells affecting maintainability)
4. **Minor** - Fix when convenient (style issues)
5. **Info** - Nice to have (suggestions)

## Suppressing False Positives

Only when absolutely necessary:

```java
@SuppressWarnings("java:S3776") // Explain why: this algorithm requires complexity
public void complexAlgorithm() {
    // ...
}
```

## Running Quick Local Check

```bash
# Install SonarLint plugin in your IDE (IntelliJ IDEA, Eclipse, VS Code)
# It will highlight issues as you code

# Or run Maven analysis
mvn sonar:sonar \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.login=$SONAR_TOKEN
```

## IDE Plugins

- **IntelliJ IDEA**: SonarLint plugin
- **Eclipse**: SonarLint plugin  
- **VS Code**: SonarLint extension

These will show SonarQube issues in real-time as you code!
