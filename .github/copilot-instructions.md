# Bertie - Copilot Instructions

## Project Overview

Bertie is an intelligent duplicate code detector and refactoring tool for Java projects. It uses multi-algorithm similarity analysis combined with intelligent refactoring strategies to help eliminate code duplication safely and efficiently.

**Status**: Detection is production-ready ✅ | Refactoring is in beta with known bugs ⚠️

## Tech Stack

- **Language**: Java 21
- **Build Tool**: Maven 3.6+
- **Key Libraries**:
  - Antikythera (`com.github.Cloud-Solutions-International:antikythera:0.1.2.8`) - Core library for Settings and AbstractCompiler
  - JavaParser 3.27.1 - Code analysis and AST manipulation
  - java-diff-utils 4.12 - Unified diff generation
  - Jackson 2.17.2 - JSON serialization
  - JUnit 5.9.3 - Testing framework
  - Mockito 5.11.0 - Mocking framework
  - SLF4J 2.0.13 - Logging

## Project Structure

```
src/main/java/com/raditha/dedup/
├── analyzer/         # Duplication detection orchestration
├── analysis/         # Data flow, type analysis, boundary refinement
├── cli/              # Command-line interface
├── clustering/       # Duplicate clustering and recommendation generation
├── config/           # Configuration management
├── detection/        # Token normalization
├── extraction/       # Statement extraction
├── filter/           # Pre-filtering chains
├── metrics/          # Metrics export (CSV, JSON)
├── model/            # Data models (clusters, sequences, variations, etc.)
├── refactoring/      # Refactoring engines and verification
└── similarity/       # Similarity algorithms (LCS, Levenshtein, Structural)

src/test/java/        # Unit and integration tests
docs/                 # Documentation
```

## Build and Test

### Build
```bash
mvn clean compile
```

### Run Tests
```bash
# Run all tests (14 known failures expected)
mvn test

# Run only passing tests
mvn test -Dtest="!ReturnValueIntegrationTest,!VariationTrackerTest,!TokenNormalizerTest"
```

### Package
```bash
mvn package
```

### Run CLI
```bash
# Analyze duplicates
mvn exec:java -Dexec.args="analyze"

# Preview refactorings
mvn exec:java -Dexec.args="refactor --mode dry-run"

# Apply interactively
mvn exec:java -Dexec.args="refactor --mode interactive"
```

## Configuration

- Configuration file: `src/main/resources/generator.yml`
- Main class: `com.raditha.dedup.cli.BertieCLI`
- Entry point: `BertieCLI.main(String[] args)`

## Coding Standards

### Java Conventions
- Use Java 21 features where appropriate (text blocks, records, pattern matching, etc.)
- Follow standard Java naming conventions (camelCase for methods/variables, PascalCase for classes)
- Use `@SuppressWarnings("java:S106")` when System.out is intentional (CLI output)
- Write comprehensive Javadoc for public APIs, especially class-level documentation
- Keep methods focused and single-purpose

### Documentation
- Include Javadoc for all public classes and methods
- Use `/**` style comments for class and method documentation
- Document parameters with `@param`, returns with `@return`, and exceptions with `@throws`
- Include usage examples in class-level Javadoc where helpful

### Testing
- Use JUnit 5 (jupiter) for all tests
- Use `@BeforeAll` for one-time setup (often with `Settings.loadConfigMap()`)
- Use `@BeforeEach` for per-test setup
- Use descriptive test method names (e.g., `testSimpleDuplicate`, `testNoDuplicates`)
- Use text blocks (`"""`) for multi-line test code
- Use `StaticJavaParser.parse()` for simple tests
- Use `AntikytheraRunTime.getCompilationUnit()` for complex test files
- Prefer `assertTrue/assertFalse` with meaningful conditions over complex assertions

### Code Organization
- Keep related functionality together in packages
- Use composition over inheritance
- Prefer immutability where possible
- Use records for simple data classes
- Keep constructors simple; do complex initialization in factory methods if needed

## Important Constraints

### Known Issues (DO NOT attempt to fix unless specifically asked)
The refactoring features have 14 known test failures representing P0 bugs:
1. **Argument Extraction** - May use wrong values in some cases
2. **Return Value Detection** - Can select incorrect variable to return
3. **Type Inference** - Incomplete for complex expressions
4. **Literal Normalization** - String literal matching issues

See `docs/FUNCTIONAL_EQUIVALENCE_GAPS.md` and `docs/P0_GAP_FIXES_README.md` for details.

### Safe Features (Production-Ready)
- ✅ Duplicate detection (`analyze` command)
- ✅ Dry-run preview (`--mode dry-run`)
- ✅ Metrics export (`--export csv/json`)

### Use with Caution
- ⚠️ Interactive refactoring - Manual review required
- ⚠️ Simple extractions usually work
- ⚠️ Complex refactorings may have edge cases

### Not Recommended
- ❌ Batch mode - Auto-apply disabled due to bugs
- ❌ Production CI/CD without review

## Refactoring Strategies

Bertie implements four automatic refactoring strategies:
1. **Extract Helper Method** - Extracts duplicate code into reusable methods
2. **Extract to @BeforeEach** - Consolidates duplicate test setup code
3. **Extract to @ParameterizedTest** - Converts similar tests into parameterized tests
4. **Extract to Utility Class** - Moves stateless helpers to utility classes

The `RefactoringRecommendationGenerator` selects the best strategy based on duplicate characteristics.

## Dependencies

### Core Dependencies
- **Antikythera**: Provides `Settings`, `AbstractCompiler`, `AntikytheraRunTime`
- **JavaParser**: AST manipulation and code analysis
- **java-diff-utils**: Diff generation for previewing changes

### AI Integration (Optional)
- Gemini AI for method naming (requires `GEMINI_API_KEY` environment variable)
- Configured in `generator.yml` under `ai_service` section
- Currently disabled by default (no API key)

## When Making Changes

### For Detection/Analysis Features
- Ensure changes don't break existing duplicate detection
- Test with various code patterns (nested blocks, loops, conditionals)
- Verify performance with large files
- Update metrics export if adding new data points

### For Refactoring Features
- Changes to refactoring must not break compilation of refactored code
- Add rollback/backup mechanisms for destructive operations
- Test with both simple and complex code patterns
- Consider edge cases around variable scoping, type inference, and data flow

### For CLI Features
- Maintain backward compatibility with existing command-line arguments
- Update help text (`--help`) when adding new options
- Ensure proper error messages and exit codes
- Test both success and failure scenarios

### For Configuration
- Don't break existing `generator.yml` configurations
- Provide sensible defaults for new options
- Document new configuration options in comments

## Testing Strategy

- Write unit tests for individual components (analyzers, extractors, calculators)
- Write integration tests for end-to-end workflows
- Use test resources in `src/test/resources/` for configuration
- Test both positive cases (duplicates found) and negative cases (no duplicates)
- For refactoring tests, verify the refactored code compiles and behaves correctly

## Performance Considerations

- Use `maximal_only: true` for large codebases to limit sequence generation
- Set appropriate `max_window_growth` to prevent exponential growth
- Consider memory usage when processing large files
- Use streaming where possible for metrics export

## Security Notes

- Never commit API keys (use environment variables)
- Sanitize file paths to prevent directory traversal
- Validate user input in CLI arguments
- Be cautious with code generation/modification features
